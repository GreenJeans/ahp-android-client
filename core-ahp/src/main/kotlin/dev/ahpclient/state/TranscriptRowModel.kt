package dev.ahpclient.state

/**
 * What the transcript list shows. A message, a question or a notice is a [Single] row as it is. The agent's work between them,
 * a run of reasoning and tool calls that can be thirty to a turn, is one [Steps] row that folds to a line ("Used 4 tools")
 * and opens to the steps, as the Agents panel does. Pure, so it is tested without a UI.
 */
sealed interface TranscriptRowModel {
    /** Unique within a transcript and stable while the chat grows, so a list can key on it. */
    val key: String

    data class Single(val item: TranscriptItem) : TranscriptRowModel {
        override val key: String get() = item.key
    }

    /** Two or more reasoning and tool-call items in a row. [items] holds only those two kinds, oldest first. */
    data class Steps(override val key: String, val items: List<TranscriptItem>) : TranscriptRowModel {
        private val tools: List<TranscriptItem.Tool> = items.filterIsInstance<TranscriptItem.Tool>()

        /** How many tool calls the run holds. */
        val toolCount: Int get() = tools.size

        val failedCount: Int get() = tools.count { it.state == ToolState.FAILED }

        /** Distinct MCP server names reported across the tool calls in this step group (E5), in order of appearance. */
        val mcpServers: List<String> get() = tools.mapNotNull { it.mcpServerName?.takeIf(String::isNotBlank) }.distinct()

        /** Formatted durations of completed tool calls in this step group (E5), in order. */
        val durations: List<String> get() = tools.mapNotNull { it.durationText?.takeIf(String::isNotBlank) }

        /** The agent is still at it: a tool is running (or being prepared), or the last thing it does is think. */
        val running: Boolean
            get() = tools.any { it.state == ToolState.RUNNING || it.state == ToolState.STREAMING } ||
                items.filterIsInstance<TranscriptItem.Reasoning>().any { it.live }

        /** Something here is waiting on the person, so the run must not stay folded over it. */
        val needsYou: Boolean get() = tools.any { it.state == ToolState.WAITING_FOR_APPROVAL || it.state == ToolState.SIGN_IN_REQUIRED }

        /** The one line when folded: what the agent is doing now, or what it did. */
        val title: String
            get() = when {
                needsYou -> "Waiting on you"
                running -> "Working…"
                toolCount == 0 -> "Thought"
                toolCount == 1 -> "Used 1 tool"
                else -> "Used $toolCount tools"
            }

        /**
         * Under the title: the step in progress (its live progress message from E3 when present, else the tool name and summary),
         * else which tools were used and how many failed. Null when there is nothing to add.
         */
        val detail: String?
            get() = when {
                needsYou -> tools.lastOrNull { it.state == ToolState.WAITING_FOR_APPROVAL || it.state == ToolState.SIGN_IN_REQUIRED }?.let(::describe)
                running -> tools.lastOrNull { it.state == ToolState.RUNNING && !it.progress.isNullOrBlank() }?.progress
                    ?: items.lastOrNull()?.let { if (it is TranscriptItem.Tool) describe(it) else "Thinking…" }
                else -> listOfNotNull(
                    distinctNames().takeIf { it.isNotEmpty() },
                    failedCount.takeIf { it > 0 }?.let { "$it failed" },
                ).joinToString(" · ").ifEmpty { null }
            }

        private fun describe(tool: TranscriptItem.Tool): String = listOfNotNull(tool.name, tool.summary?.takeIf { it.isNotBlank() }).joinToString(": ")

        private fun distinctNames(): String {
            val names = tools.map { it.name }.filter { it.isNotBlank() }.distinct()
            return if (names.size <= MAX_NAMES) names.joinToString(", ") else names.take(MAX_NAMES).joinToString(", ") + "…"
        }

        private companion object {
            const val MAX_NAMES = 3
        }
    }

    companion object {
        /** Prefix of a [Steps] key, so it can never equal the key of an item. */
        const val STEPS_KEY_PREFIX = "steps:"

        /**
         * Folds each run of two or more reasoning and tool-call items into one [Steps] row. A run of one stays as it is: folding
         * a lone step would only hide it behind a line that says the same. A run is keyed by its first item, which does not change
         * as the run grows, so what the person opened stays open while the agent works.
         */
        fun from(items: List<TranscriptItem>): List<TranscriptRowModel> {
            val rows = ArrayList<TranscriptRowModel>(items.size)
            var run = ArrayList<TranscriptItem>()
            fun flush() {
                when (run.size) {
                    0 -> Unit
                    1 -> rows += Single(run.single())
                    else -> rows += Steps(STEPS_KEY_PREFIX + run.first().key, run)
                }
                run = ArrayList()
            }
            for (item in items) {
                if (item is TranscriptItem.Reasoning || item is TranscriptItem.Tool) {
                    run += item
                } else {
                    flush()
                    rows += Single(item)
                }
            }
            flush()
            return rows
        }
    }
}
