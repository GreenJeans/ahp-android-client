package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.AnnotationsState
import com.microsoft.agenthostprotocol.generated.CanvasState

private val WHITESPACE = Regex("\\s+")

private fun oneLine(text: String?, max: Int): String? =
    text?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { LineDiff.clean(it.take(max)) }

/** A canvas as its screen shows it: bounded text with reordering characters marked, and a link only when it is a plain `https://` one. */
data class CanvasDetail(
    val title: String,
    val extension: String?,
    val canvasId: String?,
    val status: String?,
    val url: String?,
) {
    companion object {
        private const val MAX_TITLE = 120
        private const val MAX_STATUS = 300

        fun from(state: CanvasState): CanvasDetail {
            val extension = oneLine(state.extensionName, MAX_TITLE)
            return CanvasDetail(
                title = oneLine(state.title, MAX_TITLE) ?: extension ?: "Canvas",
                extension = extension,
                canvasId = oneLine(state.canvasId, MAX_TITLE),
                status = oneLine(state.status, MAX_STATUS),
                url = state.url?.takeIf { it.startsWith("https://", ignoreCase = true) && LinkTarget.of(it) is LinkTarget.Web },
            )
        }
    }
}

data class AnnotationEntryRow(val id: String, val text: String)

data class AnnotationRow(
    val id: String,
    /** The file, and the line when the annotation is anchored to one. */
    val target: String,
    val resolved: Boolean,
    val entries: List<AnnotationEntryRow>,
    val omittedEntries: Int,
)

/** A session's annotations as the sheet shows them. Everything here is host text, so it is bounded and has reordering characters marked. */
data class AnnotationsView(val rows: List<AnnotationRow>, val omitted: Int) {
    companion object {
        const val MAX_ANNOTATIONS = 200
        const val MAX_ENTRIES = 100
        const val MAX_COMMENT_CHARS = 4_000
        private const val MAX_TARGET = 240

        fun from(state: AnnotationsState): AnnotationsView = AnnotationsView(
            rows = state.annotations.take(MAX_ANNOTATIONS).map { annotation ->
                val path = oneLine(displayPath(annotation.resource), MAX_TARGET) ?: "(unknown file)"
                AnnotationRow(
                    id = annotation.id,
                    // Zero-based on the wire.
                    target = annotation.range?.let { "$path:${it.start.line + 1}" } ?: path,
                    resolved = annotation.resolved,
                    entries = annotation.entries.take(MAX_ENTRIES).map { entry ->
                        AnnotationEntryRow(
                            entry.id,
                            SessionDetail.plain(entry.text).orEmpty().take(MAX_COMMENT_CHARS).lineSequence().joinToString("\n", transform = LineDiff::clean),
                        )
                    },
                    omittedEntries = (annotation.entries.size - MAX_ENTRIES).coerceAtLeast(0),
                )
            },
            omitted = (state.annotations.size - MAX_ANNOTATIONS).coerceAtLeast(0),
        )
    }
}
