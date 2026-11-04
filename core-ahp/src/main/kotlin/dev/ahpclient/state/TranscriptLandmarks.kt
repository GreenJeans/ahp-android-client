package dev.ahpclient.state

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
    }
}
