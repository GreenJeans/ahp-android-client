package dev.ahpclient.ahp

import dev.ahpclient.tunnel.SocketClosedException
import dev.ahpclient.tunnel.TextSocket
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.AhpErrorCodes
import com.microsoft.agenthostprotocol.generated.ContentEncoding
import com.microsoft.agenthostprotocol.generated.CreateChatParams
import com.microsoft.agenthostprotocol.generated.CreateSessionParams
import com.microsoft.agenthostprotocol.generated.DispatchActionParams
import com.microsoft.agenthostprotocol.generated.DisposeChatParams
import com.microsoft.agenthostprotocol.generated.DisposeSessionParams
import com.microsoft.agenthostprotocol.generated.FetchTurnsParams
import com.microsoft.agenthostprotocol.generated.FetchTurnsResult
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.InitializeParams
import com.microsoft.agenthostprotocol.generated.InitializeResult
import com.microsoft.agenthostprotocol.generated.JsonRpcErrorCodes
import com.microsoft.agenthostprotocol.generated.ListSessionsParams
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.ReconnectParams
import com.microsoft.agenthostprotocol.generated.ReconnectResult
import com.microsoft.agenthostprotocol.generated.ResolveSessionConfigParams
import com.microsoft.agenthostprotocol.generated.ResolveSessionConfigResult
import com.microsoft.agenthostprotocol.generated.ResourceReadParams
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import com.microsoft.agenthostprotocol.generated.SUPPORTED_PROTOCOL_VERSIONS
import com.microsoft.agenthostprotocol.generated.StateAction
import com.microsoft.agenthostprotocol.generated.SubscribeParams
import com.microsoft.agenthostprotocol.generated.SubscribeResult
import com.microsoft.agenthostprotocol.generated.SubscribeView
import com.microsoft.agenthostprotocol.generated.UnsupportedProtocolVersionErrorData
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val ROOT_CHANNEL = "ahp-root://"

/** What the host pushes without being asked. */
sealed interface ServerNotification {
    /** A state action with its server sequence number: the unit the reducers consume. */
    data class Action(val envelope: ActionEnvelope) : ServerNotification

    /** Any other notification, routed by method name (`root/sessionAdded`, `root/sessionRemoved`, ...). */
    data class Named(val method: String, val params: JsonObject) : ServerNotification

    /** A frame that could not be decoded; the state mirror may have missed an update, so the caller should resubscribe. */
    data class Undecodable(val method: String?, val cause: Throwable) : ServerNotification
}

sealed class AhpException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The host answered with a JSON-RPC error. */
    class Rpc(val code: Int, message: String, val data: JsonElement?) : AhpException("AHP error $code: $message")

    /** Error -32005: the host speaks none of the offered versions. */
    class UnsupportedProtocolVersion(val offered: List<String>, val supported: List<String>) :
        AhpException("Host speaks none of $offered; it supports $supported")

    class ConnectionClosed(cause: Throwable?) : AhpException("The AHP connection closed", cause)

    class Timeout(method: String) : AhpException("Timed out waiting for '$method'")

    class Protocol(detail: String, cause: Throwable? = null) : AhpException("Unexpected AHP message: $detail", cause)
}

/**
 * JSON-RPC 2.0 over a [TextSocket] speaking AHP. Every outbound frame goes through [policy]; the socket is private, so
 * there is no other way to send. The one deliberate exception is the `-32601 Method not found` reply to a request the
 * host sends us: an error response changes no state and is not a call this client makes.
 *
 * Notifications are delivered on a single-collector [notifications] flow backed by an unbounded buffer, so nothing that
 * arrives between `subscribe` returning and the collector starting is lost.
 */
class AhpConnection(
    private val socket: TextSocket,
    scope: CoroutineScope,
    private val policy: CapabilityPolicy,
    val clientId: String = UUID.randomUUID().toString(),
    initialServerSeq: Long = 0,
    private val requestTimeoutMs: Long = 30_000,
    private val json: Json = Ahp.json,
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val inbox = Channel<ServerNotification>(Channel.UNLIMITED)
    private val closedBy = AtomicReference<AhpException.ConnectionClosed?>(null)

    @Volatile
    var lastServerSeq: Long = initialServerSeq
        private set

    val notifications: Flow<ServerNotification> = inbox.receiveAsFlow()

    /** Whether this connection's tier lets it send any action at all. */
    val canDispatch: Boolean get() = policy.canDispatch

    /** Whether this connection's tier lets it create and remove sessions and chats. */
    val canManage: Boolean get() = policy.canManage

    init {
        scope.launch { readLoop() }
    }

    suspend fun initialize(clientInfo: Implementation, subscriptions: List<String> = listOf(ROOT_CHANNEL)): InitializeResult {
        val params = InitializeParams(
            channel = ROOT_CHANNEL,
            protocolVersions = SUPPORTED_PROTOCOL_VERSIONS,
            clientId = clientId,
            clientInfo = clientInfo,
            initialSubscriptions = subscriptions,
        )
        val result = request("initialize", InitializeParams.serializer(), params, InitializeResult.serializer())
        lastServerSeq = result.serverSeq
        return result
    }

    suspend fun reconnect(subscriptions: List<String>): ReconnectResult =
        request(
            "reconnect",
            ReconnectParams.serializer(),
            ReconnectParams(channel = ROOT_CHANNEL, clientId = clientId, lastSeenServerSeq = lastServerSeq, subscriptions = subscriptions),
            ReconnectResult.serializer(),
        )

    suspend fun listSessions(cursor: String? = null, limit: Long? = null): ListSessionsResult =
        request("listSessions", ListSessionsParams.serializer(), ListSessionsParams(channel = ROOT_CHANNEL, limit = limit, cursor = cursor), ListSessionsResult.serializer())

    suspend fun subscribe(channel: String, view: SubscribeView? = null): SubscribeResult =
        request("subscribe", SubscribeParams.serializer(), SubscribeParams(channel = channel, view = view), SubscribeResult.serializer())

    suspend fun fetchTurns(chat: String, cursor: String? = null): FetchTurnsResult =
        request("fetchTurns", FetchTurnsParams.serializer(), FetchTurnsParams(channel = chat, cursor = cursor), FetchTurnsResult.serializer())

    /**
     * Reads the content at [uri], a content address the host itself published (a changed file's side). Text is asked for as
     * UTF-8; a host may still answer in base64. Read only. The caller decides which addresses to ask for.
     */
    suspend fun resourceRead(uri: String): ResourceReadResult =
        request(
            "resourceRead",
            ResourceReadParams.serializer(),
            ResourceReadParams(channel = ROOT_CHANNEL, uri = uri, encoding = ContentEncoding.UTF8),
            ResourceReadResult.serializer(),
        )

    suspend fun ping() {
        requestRaw("ping", buildJsonObject { put("channel", ROOT_CHANNEL) })
    }

    /**
     * Creates the session at [uri], which the client picks. The host answers once it has accepted the request; the session
     * then reaches `ready` (or fails) on its own channel, which the caller watches. Refused with [CapabilityDeniedException],
     * before anything is sent, unless the tier lists the command. [config] is what the host itself resolved for this agent and
     * folder (see [resolveSessionConfig]), with the person's choices in it; null leaves every setting to the host.
     */
    suspend fun createSession(uri: String, provider: String?, workingDirectories: List<String>?, config: Map<String, JsonElement>? = null) {
        val params = CreateSessionParams(channel = uri, provider = provider, workingDirectories = workingDirectories, config = config)
        requestRaw("createSession", json.encodeToJsonElement(CreateSessionParams.serializer(), params).jsonObject)
    }

    /**
     * Asks the host which settings it would offer for a new session of [provider] in [workingDirectory], given the values
     * already chosen in [config]. The answer can change as values are chosen, so the caller asks again after each choice.
     * Changes nothing on the host.
     */
    suspend fun resolveSessionConfig(provider: String, workingDirectory: String, config: Map<String, JsonElement>?): ResolveSessionConfigResult =
        request(
            "resolveSessionConfig",
            ResolveSessionConfigParams.serializer(),
            ResolveSessionConfigParams(channel = ROOT_CHANNEL, provider = provider, workingDirectory = workingDirectory, config = config),
            ResolveSessionConfigResult.serializer(),
        )

    /** Creates the chat at [chatUri], which the client picks, in the session at [sessionUri]. */
    suspend fun createChat(sessionUri: String, chatUri: String) {
        val params = CreateChatParams(channel = sessionUri, chat = chatUri)
        requestRaw("createChat", json.encodeToJsonElement(CreateChatParams.serializer(), params).jsonObject)
    }

    /** Removes the session at [uri] and everything in it. Cannot be undone. */
    suspend fun disposeSession(uri: String) {
        requestRaw("disposeSession", json.encodeToJsonElement(DisposeSessionParams.serializer(), DisposeSessionParams(channel = uri)).jsonObject)
    }

    /** Removes the chat at [uri]. Cannot be undone. */
    suspend fun disposeChat(uri: String) {
        requestRaw("disposeChat", json.encodeToJsonElement(DisposeChatParams.serializer(), DisposeChatParams(channel = uri)).jsonObject)
    }

    /** `unsubscribe` is a notification in AHP 1.0: fire and forget. */
    fun unsubscribe(channel: String) {
        policy.check("unsubscribe")
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "unsubscribe")
            put("params", buildJsonObject { put("channel", channel) })
        }
        socket.send(frame.toString())
    }

    /**
     * Sends one client action, a notification the host answers by echoing it back on the channel (accepted, with this
     * client's `origin`) or with a rejection reason. Refused with [CapabilityDeniedException], before anything is sent,
     * unless the tier lists this action type. [clientSeq] is chosen by the caller, who owns the client's identity, so it
     * can stay increasing across connections.
     *
     * @throws AhpException.ConnectionClosed when the socket is gone, so an action that never left the device is never reported as sent.
     */
    fun dispatchAction(channel: String, action: StateAction, clientSeq: Long) {
        policy.check(CapabilityPolicy.DISPATCH_ACTION, actionTypeOf(action))
        closedBy.get()?.let { throw it }
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", CapabilityPolicy.DISPATCH_ACTION)
            put("params", json.encodeToJsonElement(DispatchActionParams.serializer(), DispatchActionParams(channel = channel, clientSeq = clientSeq, action = action)))
        }
        if (!socket.send(frame.toString())) throw closedBy.get() ?: AhpException.ConnectionClosed(null)
    }

    private fun actionTypeOf(action: StateAction): String? =
        try {
            (json.encodeToJsonElement(StateAction.serializer(), action).jsonObject["type"] as? JsonPrimitive)?.contentOrNull
        } catch (_: SerializationException) {
            null
        }

    fun close() {
        socket.close()
    }

    private suspend fun <P, R> request(method: String, paramsSerializer: KSerializer<P>, params: P, resultSerializer: KSerializer<R>): R {
        val encoded = json.encodeToJsonElement(paramsSerializer, params).jsonObject
        val result = requestRaw(method, encoded, requestOffered = if (method == "initialize") (params as InitializeParams).protocolVersions else null)
        return try {
            json.decodeFromJsonElement(resultSerializer, result)
        } catch (e: SerializationException) {
            throw AhpException.Protocol("'$method' result does not match the expected shape", e)
        } catch (e: IllegalArgumentException) {
            throw AhpException.Protocol("'$method' result does not match the expected shape", e)
        }
    }

    private suspend fun requestRaw(method: String, params: JsonObject, requestOffered: List<String>? = null): JsonElement {
        policy.check(method)
        closedBy.get()?.let { throw it }
        val id = nextId.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        // The reader may have closed between the first check and registering: fail now instead of waiting for a timeout.
        closedBy.get()?.let {
            pending.remove(id)
            throw it
        }
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        if (!socket.send(frame.toString())) {
            pending.remove(id)
            throw closedBy.get() ?: AhpException.ConnectionClosed(null)
        }
        try {
            return withTimeout(requestTimeoutMs) { response.await() }
        } catch (_: TimeoutCancellationException) {
            throw AhpException.Timeout(method)
        } catch (e: AhpException.Rpc) {
            throw if (e.code == AhpErrorCodes.UNSUPPORTED_PROTOCOL_VERSION && requestOffered != null) toUnsupported(e, requestOffered) else e
        } finally {
            pending.remove(id)
        }
    }

    private fun toUnsupported(error: AhpException.Rpc, offered: List<String>): AhpException {
        val supported = try {
            error.data?.let { json.decodeFromJsonElement(UnsupportedProtocolVersionErrorData.serializer(), it).supportedVersions }
        } catch (_: SerializationException) {
            null
        }
        return AhpException.UnsupportedProtocolVersion(offered, supported ?: emptyList())
    }

    private suspend fun readLoop() {
        var failure: Throwable? = null
        try {
            while (true) handle(socket.receive())
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Throwable) {
            failure = e
        } finally {
            val closed = AhpException.ConnectionClosed(failure)
            closedBy.set(closed)
            pending.values.forEach { it.completeExceptionally(closed) }
            val clean = failure is SocketClosedException && failure.isClean
            inbox.close(if (clean) null else closed)
        }
    }

    private fun handle(text: String) {
        val frame = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            inbox.trySend(ServerNotification.Undecodable(null, e))
            return
        }
        val method = (frame["method"] as? JsonPrimitive)?.contentOrNull
        val id = (frame["id"] as? JsonPrimitive)?.longOrNull
        when {
            method != null && frame["id"] == null -> handleNotification(method, frame["params"] as? JsonObject ?: JsonObject(emptyMap()))
            method != null && id != null -> replyMethodNotFound(id)
            id != null -> handleResponse(id, frame)
            else -> inbox.trySend(ServerNotification.Undecodable(method, AhpException.Protocol("frame has neither a method nor an id")))
        }
    }

    private fun handleNotification(method: String, params: JsonObject) {
        if (method != "action") {
            inbox.trySend(ServerNotification.Named(method, params))
            return
        }
        try {
            val envelope = json.decodeFromJsonElement(ActionEnvelope.serializer(), params)
            // A rejected action changed nothing, so it must not move the resume point: the host may reuse its number.
            if (envelope.rejectionReason == null && envelope.serverSeq > lastServerSeq) lastServerSeq = envelope.serverSeq
            inbox.trySend(ServerNotification.Action(envelope))
        } catch (e: SerializationException) {
            inbox.trySend(ServerNotification.Undecodable(method, e))
        } catch (e: IllegalArgumentException) {
            inbox.trySend(ServerNotification.Undecodable(method, e))
        }
    }

    private fun handleResponse(id: Long, frame: JsonObject) {
        val waiting = pending[id] ?: return
        val error = frame["error"] as? JsonObject
        if (error == null) {
            waiting.complete(frame["result"] ?: JsonNull)
            return
        }
        val code = (error["code"] as? JsonPrimitive)?.longOrNull?.toInt() ?: JsonRpcErrorCodes.INTERNAL_ERROR
        val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "(no message)"
        waiting.completeExceptionally(AhpException.Rpc(code, message, error["data"]))
    }

    private fun replyMethodNotFound(id: Long) {
        val reply = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", JsonRpcErrorCodes.METHOD_NOT_FOUND)
                put("message", "Method not found")
            })
        }
        socket.send(reply.toString())
    }
}
