package dev.ahpclient.ahp

import dev.ahpclient.platform.platformSynchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The limits for one direction of a windowed subscription (AHP windowed delivery), in UTF-16 bytes: [windowBytes] is how much may be
 * sent and not yet credited, a soft target; [maximumFrameBytes] is the largest single JSON-RPC message, a hard one.
 */
data class ReceiveLimits(val windowBytes: Long, val maximumFrameBytes: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("windowBytes", windowBytes)
        put("maximumFrameBytes", maximumFrameBytes)
    }

    /** Whether these limits are no larger than [offer], as a host's accepted limits must be. */
    fun within(offer: ReceiveLimits): Boolean = windowBytes <= offer.windowBytes && maximumFrameBytes <= offer.maximumFrameBytes

    companion object {
        /** The protocol's floor for [maximumFrameBytes]. */
        const val MINIMUM_FRAME_BYTES = 1024L

        /** Limits read from the host, or null when they are missing, not whole numbers, or below the protocol's floor. */
        fun from(element: JsonElement?): ReceiveLimits? {
            val obj = element as? JsonObject ?: return null
            val window = (obj["windowBytes"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            val frame = (obj["maximumFrameBytes"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            return if (window > 0 && frame >= MINIMUM_FRAME_BYTES) ReceiveLimits(window, frame) else null
        }
    }
}

/** A windowed channel the host broke the rules on. Only that channel is given up; the connection carries on. */
class ChannelWindowException(val channel: String, detail: String) : Exception("Windowed delivery on $channel: $detail")

/**
 * The counters for one windowed subscription, both directions (AHP windowed delivery). Sizes are the received or sent JSON text in
 * UTF-16 bytes, two for each UTF-16 unit, which is how a Kotlin string counts. Pure apart from the lock: [AhpConnection] reads and writes
 * the socket, this decides what is counted, joined, credited and allowed. Data is released as soon as it is handed on to be applied, so
 * credit never waits on the person.
 */
class ChannelWindow(val channel: String, val receive: ReceiveLimits, val send: ReceiveLimits) {
    private val lock = Any()
    private var received = 0L
    private var credited = 0L
    private var partial: StringBuilder? = null
    private var sent = 0L
    private var peerConsumed = 0L
    private val credits = MutableStateFlow(0L)

    /**
     * Counts one data message, [text] as it came, sent whole or as a `channel/frame` fragment. Returns the message to handle: [text]
     * itself when whole, the joined text when [params] hold the final fragment, or null while more fragments are to come.
     *
     * @throws ChannelWindowException for a fragment with no text, a data message in the middle of a fragmented one, or a joined message
     *   past [MAX_JOINED_CHARS].
     */
    fun receive(text: String, method: String, params: JsonObject): String? = platformSynchronized(lock) {
        received += text.length * 2L
        if (method != FRAME) {
            if (partial != null) throw ChannelWindowException(channel, "a message arrived in the middle of a fragmented one")
            return@platformSynchronized text
        }
        val data = (params["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (data.isNullOrEmpty()) throw ChannelWindowException(channel, "a fragment had no data")
        val buffer = partial ?: StringBuilder().also { partial = it }
        if (buffer.length.toLong() + data.length > MAX_JOINED_CHARS) throw ChannelWindowException(channel, "a message was longer than $MAX_JOINED_CHARS characters")
        buffer.append(data)
        if ((params["final"] as? JsonPrimitive)?.booleanOrNull != true) return@platformSynchronized null
        partial = null
        buffer.toString()
    }

    /**
     * The `consumedBytes` to report now, or null when credit can wait: once three quarters of the window is uncredited. The host waits
     * only when a whole window is outstanding, so by then this has always asked for credit.
     */
    fun creditDue(): Long? = platformSynchronized(lock) {
        if ((received - credited) * 4 < receive.windowBytes * 3) return@platformSynchronized null
        credited = received
        received
    }

    /** Records the host's credit for what this app sent. A total, so a smaller one than before is ignored; more than was sent is capped. */
    fun acceptCredit(consumedBytes: Long) {
        platformSynchronized(lock) {
            if (consumedBytes <= peerConsumed) return
            peerConsumed = minOf(consumedBytes, sent)
        }
        credits.value = consumedBytes
    }

    /** Whether [text] fits the host's frame limit, so it may be sent whole. */
    fun fitsFrame(text: String): Boolean = text.length * 2L <= send.maximumFrameBytes

    /** Counts [text] as sent and returns true, or returns false, counting nothing, while the host's window is full. */
    fun trySend(text: String): Boolean = platformSynchronized(lock) {
        if (sent - peerConsumed >= send.windowBytes) return@platformSynchronized false
        sent += text.length * 2L
        true
    }

    /** Waits up to [timeoutMs] for the host's window to have room. @return false when it did not. */
    suspend fun awaitRoom(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { credits.first { platformSynchronized(lock) { sent - peerConsumed < send.windowBytes } } } != null

    companion object {
        const val FRAME = "channel/frame"
        const val CREDIT = "channel/credit"
        const val SNAPSHOT = "channel/snapshot"

        /** What a chat offers to receive: room for large snapshots without frames so large a phone holds several at once. */
        val CHAT_RECEIVE = ReceiveLimits(windowBytes = 4L * 1024 * 1024, maximumFrameBytes = 1024L * 1024)

        /** The longest message joined from fragments, in characters (64 MiB as counted here): twice what the tunnel carries in one message. */
        const val MAX_JOINED_CHARS = 32L * 1024 * 1024
    }
}
