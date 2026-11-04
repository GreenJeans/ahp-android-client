package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.MessageAttachment
import com.microsoft.agenthostprotocol.generated.MessageAttachmentEmbeddedResource
import com.microsoft.agenthostprotocol.generated.MessageAttachmentKind
import com.microsoft.agenthostprotocol.generated.MessageEmbeddedResourceAttachment
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A file the person attached to the message they are writing: an image or a plain text file, held as picked. It goes to the host
 * as an embedded resource (the bytes inside the message), which is the one attachment kind a phone can build from a file it was
 * handed: the others point at a path or a channel on the host, and the host supplies those, never the phone.
 */
class ComposerAttachment private constructor(label: String, contentType: String, val bytes: ByteArray, internal val supplied: HostSupplied?) {
    constructor(label: String, contentType: String, bytes: ByteArray) : this(label, contentType, bytes, null)

    /** What the person sees on the chip and the agent sees as the file's name. Bounded, and never blank. */
    val label: String = label.trim().take(Attachments.MAX_LABEL).ifBlank { "attachment" }

    /** Lower case and without parameters, so `Text/Plain; charset=utf-8` is `text/plain`. */
    val contentType: String = contentType.substringBefore(';').trim().lowercase()

    val isImage: Boolean get() = contentType.startsWith("image/")

    /** True for a mention the host offered while the person typed (`@file`): the host built it and it is sent back as the host wrote it. */
    val fromHost: Boolean get() = supplied != null

    /** This attachment as it goes on the wire for a message that reads [text]; see [Completions.wires]. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun toWire(text: String): MessageAttachment = supplied?.wireFor(text) ?: MessageAttachmentEmbeddedResource(
        MessageEmbeddedResourceAttachment(
            label = label,
            displayKind = if (isImage) "image" else "document",
            type = MessageAttachmentKind.EMBEDDED_RESOURCE,
            data = Base64.Default.encode(bytes),
            contentType = contentType,
        ),
    )

    internal companion object {
        /** A mention the host offered: [attachment] is kept whole, so whatever the host put in it (including `_meta`) goes back to it unchanged. */
        fun supplied(label: String, supplied: HostSupplied) = ComposerAttachment(label, "", ByteArray(0), supplied)
    }
}

/** Why a set of attachments cannot be sent. Nothing is sent while there is one. */
sealed interface AttachmentProblem {
    data class TooMany(val limit: Int) : AttachmentProblem

    data class TooBig(val label: String, val limitBytes: Int) : AttachmentProblem

    data class TotalTooBig(val limitBytes: Int) : AttachmentProblem

    data class Unsupported(val label: String) : AttachmentProblem

    data class Empty(val label: String) : AttachmentProblem

    /** The model in use is one the host says cannot see images. */
    data object NoVision : AttachmentProblem
}

/**
 * What a phone will attach. The limits are this app's own, chosen to keep one message well inside a single connection frame; the
 * protocol sets none, so a host may refuse a message these allow, and its own words are then shown.
 */
object Attachments {
    const val MAX_COUNT = 5
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    const val MAX_LABEL = 80

    private val IMAGES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    private val TEXT_LIKE = setOf("application/json", "application/xml", "application/x-yaml", "application/yaml")

    /** Types the agent can read straight from the bytes: the common image formats and anything textual. */
    fun accepts(contentType: String): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        return type in IMAGES || type.startsWith("text/") || type in TEXT_LIKE
    }

    /**
     * The first reason [attachments] cannot go, or null when they can. [vision] is whether the model in use sees images: false
     * (the host says so) refuses images, and null (the host did not say) lets them through.
     */
    fun problem(all: List<ComposerAttachment>, vision: Boolean?): AttachmentProblem? {
        // The limits are for files the phone holds; a mention is only a pointer the host made, so it is the host's to refuse.
        val attachments = all.filterNot { it.fromHost }
        if (attachments.size > MAX_COUNT) return AttachmentProblem.TooMany(MAX_COUNT)
        for (file in attachments) {
            if (!accepts(file.contentType)) return AttachmentProblem.Unsupported(file.label)
            if (file.bytes.isEmpty()) return AttachmentProblem.Empty(file.label)
            if (file.bytes.size > MAX_BYTES) return AttachmentProblem.TooBig(file.label, MAX_BYTES)
        }
        if (attachments.sumOf { it.bytes.size.toLong() } > MAX_TOTAL_BYTES) return AttachmentProblem.TotalTooBig(MAX_TOTAL_BYTES)
        if (vision == false && attachments.any { it.isImage }) return AttachmentProblem.NoVision
        return null
    }

    /** [problem] in plain words. */
    fun describe(problem: AttachmentProblem): String = when (problem) {
        is AttachmentProblem.TooMany -> "Attach at most ${problem.limit} files to one message."
        is AttachmentProblem.TooBig -> "${problem.label} is larger than ${problem.limitBytes / MEGABYTE} MB."
        is AttachmentProblem.TotalTooBig -> "Together the files are larger than ${problem.limitBytes / MEGABYTE} MB. Remove one."
        is AttachmentProblem.Unsupported -> "${problem.label} is not an image or a text file, so it cannot be attached."
        is AttachmentProblem.Empty -> "${problem.label} is empty."
        AttachmentProblem.NoVision -> "The model in use cannot see images. Pick another model or remove the image."
    }

    private const val MEGABYTE = 1024 * 1024
}
