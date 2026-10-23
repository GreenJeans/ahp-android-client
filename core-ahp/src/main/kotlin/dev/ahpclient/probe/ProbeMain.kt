package dev.ahpclient.probe

import dev.ahpclient.ahp.AhpConnection
import dev.ahpclient.ahp.AhpException
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.ahp.ServerNotification
import dev.ahpclient.auth.DeviceFlowResult
import dev.ahpclient.auth.GitHubAuthException
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.state.activityState
import dev.ahpclient.state.isArchived
import dev.ahpclient.state.isRead
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.DevTunnelsException
import dev.ahpclient.tunnel.GatewayException
import dev.ahpclient.tunnel.OkHttpSocketOpener
import dev.ahpclient.tunnel.SocketClosedException
import dev.ahpclient.tunnel.TunnelGateway
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.tunnel.UpgradeRejectedException
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.SUPPORTED_PROTOCOL_VERSIONS
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.StateAction
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private const val USAGE = "usage: probe [--tunnel <name|id>] [--instance <id>] [--capture <file>] [--watch <seconds>] [--session <title-or-uri>] [--chat <title-or-uri>]"
private const val SESSION_PAGE_SIZE = 100L
private const val MAX_SESSION_PAGES = 10
private const val TITLE_WIDTH = 60
private const val DEFAULT_CHAT_WATCH_SECONDS = 30

internal data class ProbeArgs(
    val tunnel: String? = null,
    val instance: String? = null,
    val capture: Path? = null,
    val watchSeconds: Int = 0,
    val session: String? = null,
    val chat: String? = null,
) {
    companion object {
        fun parse(args: Array<String>): ProbeArgs {
            var result = ProbeArgs()
            var i = 0
            fun value(flag: String): String = args.getOrNull(++i) ?: throw IllegalArgumentException("$flag needs a value\n$USAGE")
            while (i < args.size) {
                when (val flag = args[i]) {
                    "--tunnel" -> result = result.copy(tunnel = value(flag))
                    "--instance" -> result = result.copy(instance = value(flag))
                    "--capture" -> result = result.copy(capture = Paths.get(value(flag)))
                    "--watch" -> result = result.copy(watchSeconds = value(flag).toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("--watch needs a number of seconds\n$USAGE"))
                    "--session" -> result = result.copy(session = value(flag))
                    "--chat" -> result = result.copy(chat = value(flag))
                    else -> throw IllegalArgumentException("unknown option '$flag'\n$USAGE")
                }
                i++
            }
            return result
        }
    }
}

/**
 * Step 1.6: the live parity checkpoint. A command-line twin of `demo/ahp-metrics-overlay/tunnel-attach.mjs --mode web`
 * that runs the production classes end to end: device-flow sign-in, tunnel discovery, gateway selection, AHP
 * `initialize` and `listSessions`. It never prints a token and never sends anything outside the READ tier.
 */
internal class Probe(private val args: ProbeArgs, private val out: (String) -> Unit = ::println) {
    private fun log(tag: String, message: String) = out("[${tag.padEnd(5)}] $message")

    private fun fail(message: String): Int {
        log("FAIL", message)
        return 1
    }

    suspend fun run(): Int = try {
        execute()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        fail(describe(e))
    }

    private suspend fun execute(): Int {
        val token = signIn() ?: return 1
        val devTunnels = DevTunnelsClient()

        val tunnels = devTunnels.listAgentHostTunnels(token.access)
        log("ok", "found ${tunnels.size} agent-host tunnel(s)")
        tunnels.forEach { log("", "  ${it.name}  id=${it.tunnelId} cluster=${it.clusterId} protocolv${it.protocolVersion} hosts=${it.hostConnectionCount} ports=${it.ports}") }
        val tunnel = pick(tunnels) ?: return fail("no agent-host tunnel${args.tunnel?.let { " matching '$it'" }.orEmpty()}; is Remote Tunnel Access on, signed in with this GitHub account?")
        log("info", "target ${tunnel.name} (protocolv${tunnel.protocolVersion}, hosts=${tunnel.hostConnectionCount})")

        val target = devTunnels.resolveConnectTarget(token.access, tunnel)
        log("info", "web endpoint ${target.portUriFormat}")

        val frames = FrameLog(args.capture)
        args.capture?.let { log("info", "recording frames to $it (local only, never commit)") }
        val connection = TunnelGateway(opener = RecordingOpener(OkHttpSocketOpener(), frames)).open(target, args.instance)
        val selected = connection.selected
        if (selected != null) {
            log("ok", "gateway selected ${selected.type} ${selected.instanceId} (${selected.lifecycle})")
        } else {
            log("info", "protocol v5: legacy root route, no gateway")
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ahp = AhpConnection(connection.socket, scope, CapabilityPolicy.READ)
        try {
            observe(ahp)
        } finally {
            ahp.close()
            scope.cancel()
        }
        return verifyReadOnly(frames)
    }

    private suspend fun signIn(): GitHubToken? {
        val auth = GitHubDeviceAuth()
        val code = auth.start()
        log("auth", "open ${code.verificationUri} and enter code ${code.userCode} (expires in ${code.expiresInSeconds / 60} min)")
        return when (val result = auth.poll(code)) {
            is DeviceFlowResult.Success -> result.token.also { log("ok", "signed in (token not shown)") }
            DeviceFlowResult.Expired -> null.also { fail("the device code expired before it was approved") }
            DeviceFlowResult.Denied -> null.also { fail("the sign-in was denied") }
            is DeviceFlowResult.Failed -> null.also { fail("GitHub refused the sign-in: ${result.error} ${result.description.orEmpty()}".trim()) }
        }
    }

    private fun pick(tunnels: List<TunnelInfo>): TunnelInfo? {
        val wanted = args.tunnel
        return if (wanted != null) {
            tunnels.firstOrNull { it.tunnelId == wanted || it.name.equals(wanted, ignoreCase = true) }
        } else {
            tunnels.firstOrNull { it.isOnline } ?: tunnels.firstOrNull()
        }
    }

    private suspend fun observe(ahp: AhpConnection) {
        val init = ahp.initialize(Implementation(name = "ahp-android-client-probe", version = "0.1.0"))
        log("PASS", "AHP initialize OK — host chose protocol ${init.protocolVersion}, server ${init.serverInfo?.name ?: "?"} ${init.serverInfo?.version.orEmpty()}, serverSeq ${init.serverSeq}".trim())
        log("info", "offered protocol versions $SUPPORTED_PROTOCOL_VERSIONS")
        init.snapshots.forEach { snapshot ->
            val state = Ahp.json.encodeToJsonElement(com.microsoft.agenthostprotocol.generated.SnapshotState.serializer(), snapshot.state)
            log("", "  snapshot ${snapshot.resource} fromSeq=${snapshot.fromSeq} state keys: ${(state as? kotlinx.serialization.json.JsonObject)?.keyNames() ?: "?"}")
        }

        val sessions = listAllSessions(ahp)
        val counts = sessions.groupingBy { it.status.activityState }.eachCount()
        log("PASS", "listSessions OK — ${sessions.size} session(s): ${counts.entries.joinToString { "${it.key.name.lowercase()}=${it.value}" }.ifEmpty { "none" }}")
        sessions.forEach { session ->
            val flags = listOfNotNull("read".takeIf { session.status.isRead }, "archived".takeIf { session.status.isArchived }).joinToString("+")
            log("", "  [${session.status.activityState.name.lowercase()}${if (flags.isEmpty()) "" else "+$flags"}] ${session.title.take(TITLE_WIDTH)}  chats=${session.chats?.size ?: "?"} modified=${session.modifiedAt}")
        }

        if (args.session != null) checkActiveClients(ahp, sessions, args.session)
        if (args.chat != null) traceChat(ahp, sessions, args.chat) else if (args.watchSeconds > 0) watch(ahp, args.watchSeconds)
    }

    /**
     * Phase 2 groundwork: subscribes to a session's chat, decodes its snapshot, asks for older turns if the host says
     * there are some, then applies every chat action that arrives with the library's own reducer. The report is counts
     * and type names only. A FAIL means the pinned library met traffic it does not know, which is what this exists to find.
     */
    private suspend fun traceChat(ahp: AhpConnection, sessions: List<SessionSummary>, wanted: String) {
        val target = ActiveClientsReport.pick(sessions, wanted) ?: return log("FAIL", "no session matches '$wanted'")
        val session = (ahp.subscribe(target.resource).snapshot?.state as? SnapshotState.Session)?.value
            ?: return log("FAIL", "the session snapshot holds no session state")
        val chatUri = session.defaultChat ?: session.chats.firstOrNull()?.resource ?: return log("FAIL", "the session lists no chat")
        log("info", "session is ${session.status.activityState.name.lowercase()}, ${session.chats.size} chat(s); subscribing to the first")
        val chat = (ahp.subscribe(chatUri).snapshot?.state as? SnapshotState.Chat)?.value
            ?: return log("FAIL", "the chat snapshot holds no chat state")
        val trace = ChatTrace(chat)
        log("PASS", "chat snapshot decoded: turns=${chat.turns.size} activeTurn=${chat.activeTurn != null} olderTurnsAvailable=${chat.turnsNextCursor != null} parts=${trace.partKinds()}")
        chat.turnsNextCursor?.let { cursor ->
            ahp.fetchTurns(chatUri, cursor)
            log("info", "asked for older turns (they arrive as a chat/turnsLoaded action)")
        }
        val seconds = if (args.watchSeconds > 0) args.watchSeconds else DEFAULT_CHAT_WATCH_SECONDS
        log("info", "applying chat actions for ${seconds}s: use or start work in the session in VS Code now")
        withTimeoutOrNull(seconds * 1000L) {
            ahp.notifications.collect { note ->
                if (note is ServerNotification.Action && note.envelope.channel == chatUri) trace.apply(note.envelope.action)
            }
        }
        log("info", "actions applied: ${trace.actionTypes.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }}")
        log("info", "transcript now: turns=${trace.state.turns.size} activeTurn=${trace.state.activeTurn != null} parts=${trace.partKinds()}")
        if (trace.ok) {
            log("PASS", "every chat action decoded and reduced (${trace.actionTypes.values.sum()} applied)")
        } else {
            log("FAIL", "unknown action types: ${trace.unknownTypes.ifEmpty { setOf("none") }.joinToString()}; reduce failures: ${trace.reduceFailures}")
        }
        ahp.unsubscribe(chatUri)
        ahp.unsubscribe(target.resource)
    }

    /**
     * Subscribes to one session and reports who the host lists as an active client. This app never sends
     * `session/activeClientSet`, so its own client id must not be among them: the host-side proof that watching a
     * session leaves no trace there.
     */
    private suspend fun checkActiveClients(ahp: AhpConnection, sessions: List<SessionSummary>, wanted: String) {
        val target = ActiveClientsReport.pick(sessions, wanted) ?: run {
            log("FAIL", "no session matches '$wanted'")
            return
        }
        log("info", "subscribing to '${target.title.take(TITLE_WIDTH)}' (${target.status.activityState.name.lowercase()})")
        val snapshot = ahp.subscribe(target.resource).snapshot
        val state = (snapshot?.state as? SnapshotState.Session)?.value
        if (state == null) {
            log("FAIL", "the subscribe result holds no session snapshot")
            return
        }
        val report = ActiveClientsReport.from(state, ahp.clientId)
        val tag = if (report.selfListed) "FAIL" else "PASS"
        log(tag, "activeClients=${report.count}${if (report.names.isEmpty()) "" else " (${report.names.joinToString()})"}; this client ${if (report.selfListed) "IS" else "is not"} listed")
        ahp.unsubscribe(target.resource)
    }

    private suspend fun listAllSessions(ahp: AhpConnection): List<SessionSummary> {
        val all = mutableListOf<SessionSummary>()
        var cursor: String? = null
        repeat(MAX_SESSION_PAGES) {
            val page = ahp.listSessions(cursor = cursor, limit = SESSION_PAGE_SIZE)
            all += page.items
            cursor = page.nextCursor ?: return all
        }
        log("warn", "stopped after $MAX_SESSION_PAGES pages; more sessions exist")
        return all
    }

    private suspend fun watch(ahp: AhpConnection, seconds: Int) {
        log("info", "watching notifications for ${seconds}s (start or answer something in VS Code)")
        withTimeoutOrNull(seconds * 1000L) {
            ahp.notifications.collect { note ->
                when (note) {
                    is ServerNotification.Action -> log("", "  action ${note.envelope.channel} seq=${note.envelope.serverSeq} ${actionType(note.envelope.action)}")
                    is ServerNotification.Named -> log("", "  notification ${note.method}")
                    is ServerNotification.Undecodable -> log("warn", "  undecodable ${note.method ?: "frame"}: ${note.cause.javaClass.simpleName}")
                }
            }
        }
        log("info", "watch finished")
    }

    private fun actionType(action: StateAction): String = try {
        (Ahp.json.encodeToJsonElement(StateAction.serializer(), action).jsonObject["type"] as? JsonPrimitive)?.content ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Checks the methods that really left the device, independently of the policy that is supposed to enforce it. */
    private fun verifyReadOnly(frames: FrameLog): Int {
        val sent = frames.sentMethods
        val denied = sent.filter { method -> runCatching { CapabilityPolicy.READ.check(method) }.isFailure }
        if (denied.isNotEmpty()) return fail("non-READ methods reached the wire: $denied")
        log("PASS", "read-only verified on the wire — sent methods: $sent")
        return 0
    }

    private fun describe(e: Throwable): String = when (e) {
        is DevTunnelsException.AuthExpired -> "${e.message}"
        is DevTunnelsException, is GatewayException, is AhpException, is GitHubAuthException, is UpgradeRejectedException -> "${e.message}"
        is SocketClosedException -> "${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }
}

fun main(args: Array<String>) {
    val parsed = try {
        ProbeArgs.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    val code = runBlocking { Probe(parsed).run() }
    exitProcess(code)
}
