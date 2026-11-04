package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.CompletionItem
import com.microsoft.agenthostprotocol.generated.MessageAnnotationsAttachment
import com.microsoft.agenthostprotocol.generated.MessageAttachment
import com.microsoft.agenthostprotocol.generated.MessageAttachmentAnnotations
import com.microsoft.agenthostprotocol.generated.MessageAttachmentChat
import com.microsoft.agenthostprotocol.generated.MessageAttachmentEmbeddedResource
import com.microsoft.agenthostprotocol.generated.MessageAttachmentResource
import com.microsoft.agenthostprotocol.generated.MessageAttachmentSimple
import com.microsoft.agenthostprotocol.generated.MessageAttachmentUnknown
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the host sent for a mention, kept whole. [prefix] is the message up to the end of the inserted text when it was accepted. */
internal class HostSupplied(val attachment: MessageAttachment, val insertText: String, val prefix: String) {
    /**
     * The attachment for a message reading [text]. The host's own range (a place in the final message) is only true while
     * everything before the mention is as it was, so once that changes the range is left out rather than sent wrong.
     */
    fun wireFor(text: String): MessageAttachment = if (text.startsWith(prefix)) attachment else Completions.withoutRange(attachment)
}

/** One thing the host offered for the word being typed: what to show, and what accepting it does to the message. */
class Suggestion internal constructor(
    /** The mention's name, as the host labelled its attachment. */
    val label: String,
    /** The text that goes into the message. */
    val insertText: String,
    internal val start: Int,
    internal val end: Int,
    internal val attachment: MessageAttachment,
)

/** The message after a suggestion was accepted, and the attachment that goes with it. */
class Accepted internal constructor(val text: String, val attachment: ComposerAttachment)

/**
 * Host-supplied completions for the message being written (`@file`, `/command`): when to ask, how to read the answer, what accepting
 * one does, and which mentions are still in the message when it is sent. The host's offsets count UTF-16 units, which is how a Kotlin
 * string indexes, so they are used as they come, after being checked against the text. An answer that does not fit is dropped, never guessed at.
 */
object Completions {
    /** More than this many suggestions are not shown; a list is not a place to scroll through on a phone. */
    const val MAX_SUGGESTIONS = 20

    /** Wait this long after the last keystroke before asking the host. */
    const val DEBOUNCE_MILLIS = 250L

    /**
     * Where the word being typed at the end of [text] starts, when it begins with one of the host's [triggers]; otherwise null, and
     * then nothing is asked. A word starts after a space or line break, so `name@host` is not a mention. The cursor is taken to be at
     * the end of the text, which is where a phone keyboard leaves it as the person types.
     *
     * A host that names a space as a trigger (VS Code does) wants to be asked about what follows a command. That is honoured only
     * in a message that starts with a slash command and has gone past its name, so the host is asked about the command's arguments
     * and not after every word of an ordinary message.
     */
    fun wordStart(text: String, triggers: List<String>): Int? {
        if (triggers.isEmpty()) return null
        val start = text.indexOfLast { it.isWhitespace() } + 1
        val word = text.substring(start)
        if (triggers.any { it.isNotEmpty() && !it.isBlank() && word.startsWith(it) }) return start
        val afterCommand = text.startsWith("/") && text.any { it.isWhitespace() }
        return start.takeIf { afterCommand && triggers.any { it.isNotEmpty() && it.isBlank() } }
    }

    /** The host's items as [Suggestion]s for [text]: in the host's order, at most [MAX_SUGGESTIONS], and without any whose range does not fit the text. */
    fun suggestions(items: List<CompletionItem>, text: String): List<Suggestion> =
        items.mapNotNull { item ->
            val from = item.rangeStart
            val to = item.rangeEnd
            // No range means "insert at the cursor". One end alone, or a range outside the text, is not something to guess at.
            val (start, end) = when {
                from == null && to == null -> text.length to text.length
                from != null && to != null && from >= 0 && from <= to && to <= text.length -> from.toInt() to to.toInt()
                else -> return@mapNotNull null
            }
            Suggestion(labelOf(item.attachment) ?: item.insertText, item.insertText, start, end, item.attachment)
        }.take(MAX_SUGGESTIONS)

    /**
     * [text] with [suggestion] put in, and the mention that goes with it. [text] must be the text the suggestion was asked for:
     * null when it has changed so that the suggestion's range no longer fits.
     */
    fun accept(text: String, suggestion: Suggestion): Accepted? {
        if (suggestion.end > text.length) return null
        val result = text.substring(0, suggestion.start) + suggestion.insertText + text.substring(suggestion.end)
        val prefix = result.substring(0, suggestion.start + suggestion.insertText.length)
        val label = labelOf(suggestion.attachment) ?: suggestion.insertText
        return Accepted(result, ComposerAttachment.supplied(label, HostSupplied(suggestion.attachment, suggestion.insertText, prefix)))
    }

    /** [attached] with [added] too, unless the same mention is already there. */
    fun plus(attached: List<ComposerAttachment>, added: ComposerAttachment): List<ComposerAttachment> =
        if (attached.any { it.supplied?.attachment == added.supplied?.attachment && it.fromHost }) attached else attached + added

    /** The files and the mentions still in [text]: a mention whose words the person deleted goes with them. */
    fun live(text: String, attached: List<ComposerAttachment>): List<ComposerAttachment> =
        attached.filter { it.supplied?.insertText?.let(text::contains) ?: true }

    /** What goes on the wire for a message reading [text]: the files, then the mentions still in it, each as the host or the picker made it. */
    internal fun wires(text: String, attached: List<ComposerAttachment>): List<MessageAttachment> = live(text, attached).map { it.toWire(text) }

    private fun labelOf(attachment: MessageAttachment): String? = when (attachment) {
        is MessageAttachmentSimple -> attachment.value.label
        is MessageAttachmentEmbeddedResource -> attachment.value.label
        is MessageAttachmentResource -> attachment.value.label
        is MessageAttachmentAnnotations -> attachment.value.label
        is MessageAttachmentChat -> attachment.value.label
        is MessageAttachmentUnknown -> (attachment.raw["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }?.takeIf { it.isNotBlank() }

    internal fun withoutRange(attachment: MessageAttachment): MessageAttachment = when (attachment) {
        is MessageAttachmentSimple -> MessageAttachmentSimple(attachment.value.copy(range = null))
        is MessageAttachmentEmbeddedResource -> MessageAttachmentEmbeddedResource(attachment.value.copy(range = null))
        is MessageAttachmentResource -> MessageAttachmentResource(attachment.value.copy(range = null))
        is MessageAttachmentAnnotations -> MessageAttachmentAnnotations(attachment.value.copy(range = null))
        is MessageAttachmentChat -> MessageAttachmentChat(attachment.value.copy(range = null))
        is MessageAttachmentUnknown -> MessageAttachmentUnknown(JsonObject(attachment.raw - "range"))
    }
}
