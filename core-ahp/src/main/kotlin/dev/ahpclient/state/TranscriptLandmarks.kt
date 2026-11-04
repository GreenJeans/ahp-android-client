package dev.ahpclient.state

/** One turn's text for reader view: its [answer] (the last text part) and the [notes] written before it, oldest first. */
data class ReplyParts(val answer: String, val notes: List<String>) {
    /** Every part in the order it was written. */
    val all: List<String> get() = notes + answer
}

/**
 * The two places a reader of a long chat wants back to: the person's latest message, and the first row of the agent's reply to it.
 * Positions are indexes into the newest-first rows the transcript list shows (see [TranscriptRowModel]). Pure, so it is tested
 * without a UI.
 */
data class TranscriptLandmarks(
    /** The row of the person's latest message, or null when none is loaded. */
    val lastUser: Int?,
    /** The oldest row after that message, where the agent's reply starts; null when the agent has not answered yet. */
    val replyStart: Int?,
    /** The latest message's words, for the bar that keeps them in view. */
    val lastUserText: String?,
) {
    companion object {
        val NONE = TranscriptLandmarks(null, null, null)

        fun of(newestFirst: List<TranscriptRowModel>): TranscriptLandmarks {
            val index = newestFirst.indexOfFirst { (it as? TranscriptRowModel.Single)?.item is TranscriptItem.User }
            if (index < 0) return NONE
            val user = (newestFirst[index] as TranscriptRowModel.Single).item as TranscriptItem.User
            return TranscriptLandmarks(index, (index - 1).takeIf { it >= 0 }, user.text.trim().ifEmpty { null })
        }

        /**
         * What the agent wrote in the turn that [turnId] ends, for reading on its own: its text parts in order, without the steps
         * in between. Read back from the turn's end to the message (or the previous turn's end), so a message hidden from the
         * transcript does not let one reply run into the next. Null when the turn has no end here or wrote nothing.
         */
        fun replyText(items: List<TranscriptItem>, turnId: String): String? = replyParts(items, turnId)?.all?.joinToString("\n\n")

        /**
         * The turn's text parts split for reading: [ReplyParts.answer] is the last one, which is where an agent gives its answer, and
         * [ReplyParts.notes] are the ones before it, the notes it wrote while it worked, oldest first. Null as for [replyText].
         */
        fun replyParts(items: List<TranscriptItem>, turnId: String): ReplyParts? {
            val end = items.indexOfLast { it is TranscriptItem.TurnEnd && it.turnId == turnId }
            if (end < 0) return null
            val parts = ArrayList<String>()
            for (i in end - 1 downTo 0) {
                when (val item = items[i]) {
                    is TranscriptItem.User, is TranscriptItem.TurnEnd -> break
                    is TranscriptItem.Markdown -> item.text.takeIf { it.isNotBlank() }?.let(parts::add)
                    else -> Unit
                }
            }
            if (parts.isEmpty()) return null
            // Collected from the end, so the first is the answer and the rest are the notes in reverse.
            return ReplyParts(answer = parts.first(), notes = parts.drop(1).asReversed())
        }

        /** The turns that [replyText] has something for, so a turn that only ran tools offers nothing to read. */
        fun readableTurns(items: List<TranscriptItem>): Set<String> =
            items.filterIsInstance<TranscriptItem.TurnEnd>().map { it.turnId }.filterTo(HashSet()) { replyText(items, it) != null }
    }
}
