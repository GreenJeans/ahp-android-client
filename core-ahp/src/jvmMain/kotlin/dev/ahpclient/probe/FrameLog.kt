package dev.ahpclient.probe

import dev.ahpclient.tunnel.SocketOpener
import dev.ahpclient.tunnel.TextSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Records every frame on the wire: the method names that actually left the device (the live read-only check) and,
 * optionally, the raw frames as JSON lines for building test fixtures. Frames can contain session titles and local
 * paths, so a capture file is for local use and must never be committed (`captures/` is gitignored).
 */
internal class FrameLog(private val file: Path?, private val census: ExtensionCensus? = null) {
    private val methods = Collections.synchronizedSet(linkedSetOf<String>())

    val sentMethods: List<String> get() = synchronized(methods) { methods.toList() }

    init {
        file?.let { path ->
            path.parent?.let(Files::createDirectories)
            Files.writeString(path, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }
    }

    fun outbound(text: String) {
        methodOf(text)?.let(methods::add)
        census?.outbound(text)
        write("out", text)
    }

    fun inbound(text: String) {
        census?.inbound(text)
        write("in", text)
    }

    private fun methodOf(text: String): String? = try {
        (Json.parseToJsonElement(text).jsonObject["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun write(direction: String, text: String) {
        val path = file ?: return
        val line = buildJsonObject {
            put("t", Instant.now().toString())
            put("dir", direction)
            put("frame", text)
        }.toString() + "\n"
        synchronized(this) { Files.writeString(path, line, StandardOpenOption.APPEND) }
    }
}

internal class RecordingSocket(private val delegate: TextSocket, private val log: FrameLog) : TextSocket {
    override suspend fun receive(): String = delegate.receive().also(log::inbound)

    override fun send(text: String): Boolean {
        log.outbound(text)
        return delegate.send(text)
    }

    override fun close(code: Int, reason: String) = delegate.close(code, reason)
}

internal class RecordingOpener(private val delegate: SocketOpener, private val log: FrameLog) : SocketOpener {
    override suspend fun open(url: String, headers: Map<String, String>): TextSocket =
        RecordingSocket(delegate.open(url, headers), log)
}

/** Names of the top-level keys of a JSON object, for printing a snapshot's shape without its contents. */
internal fun JsonObject.keyNames(): String = keys.joinToString(", ")
