package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ContentEncoding
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import com.microsoft.agenthostprotocol.generated.ResourceResponsePart
import com.microsoft.agenthostprotocol.generated.ToolResultEmbeddedResourceContent
import com.microsoft.agenthostprotocol.generated.ToolResultResourceContent
import dev.ahpclient.platform.base64MimeDecode
import dev.ahpclient.platform.decodeUtf8Strict

/** Horizontal alignment of a Markdown table column. */
enum class TableAlignment { START, CENTER, END }

/**
 * A parsed GFM Markdown table, ready for both inline horizontally-scrollable rendering (with cell text wrapping instead of
 * single-line truncation) and full-screen zoomable inspection or export.
 */
data class TableData(
    val headers: List<String>,
    val alignments: List<TableAlignment>,
    val rows: List<List<String>>,
) {
    val columnCount: Int get() = headers.size
    val rowCount: Int get() = rows.size

    /**
     * Character-count hint per column (clamped between [MIN_COL_CHARS] and [MAX_COL_CHARS]), used to size columns proportionally
     * to their actual content so narrow columns stay compact and wide columns wrap cleanly without truncating text.
     */
    fun columnCharHints(): List<Int> {
        if (headers.isEmpty()) return emptyList()
        return headers.indices.map { col ->
            val headerLen = plainCellLength(headers[col])
            val maxRowLen = rows.maxOfOrNull { row -> plainCellLength(row.getOrNull(col).orEmpty()) } ?: 0
            maxOf(headerLen, maxRowLen).coerceIn(MIN_COL_CHARS, MAX_COL_CHARS)
        }
    }

    /** Formats the table as clean GitHub-Flavored Markdown for copying or sharing. */
    fun toMarkdown(): String {
        if (headers.isEmpty()) return ""
        val widths = headers.indices.map { col ->
            val h = headers[col].length
            val r = rows.maxOfOrNull { it.getOrNull(col)?.length ?: 0 } ?: 0
            maxOf(h, r, 3)
        }
        val sb = StringBuilder()
        fun appendRow(cells: List<String>) {
            sb.append("| ")
            for (col in headers.indices) {
                val cell = cells.getOrNull(col).orEmpty().replace("|", "\\|")
                sb.append(cell.padEnd(widths[col])).append(" | ")
            }
            sb.setLength(sb.length - 1)
            sb.append('\n')
        }
        appendRow(headers)
        sb.append("| ")
        for (col in headers.indices) {
            val w = widths[col]
            val sep = when (alignments.getOrNull(col) ?: TableAlignment.START) {
                TableAlignment.START -> "-".repeat(w)
                TableAlignment.CENTER -> ":" + "-".repeat((w - 2).coerceAtLeast(1)) + ":"
                TableAlignment.END -> "-".repeat((w - 1).coerceAtLeast(1)) + ":"
            }
            sb.append(sep).append(" | ")
        }
        sb.setLength(sb.length - 1)
        sb.append('\n')
        rows.forEach(::appendRow)
        return sb.toString().trimEnd()
    }

    /** Formats the table as tab-separated values (TSV) for spreadsheets. */
    fun toTsv(): String {
        if (headers.isEmpty()) return ""
        return (listOf(headers) + rows).joinToString("\n") { row ->
            headers.indices.joinToString("\t") { col ->
                row.getOrNull(col).orEmpty().replace('\t', ' ').replace('\n', ' ')
            }
        }
    }

    companion object {
        const val MIN_COL_CHARS = 6
        const val MAX_COL_CHARS = 34

        private val SEPARATOR_CELL = Regex("""^:?-+:?$""")

        private fun plainCellLength(cell: String): Int = MarkdownPlain.of(cell).trim().length

        /**
         * Parses a GFM table from [markdown] in `[startOffset, endOffset)`. Returns null if the slice does not hold a valid header
         * and separator row.
         */
        fun parseMarkdown(markdown: String, startOffset: Int = 0, endOffset: Int = markdown.length): TableData? {
            val safeStart = startOffset.coerceIn(0, markdown.length)
            val safeEnd = endOffset.coerceIn(safeStart, markdown.length)
            val lines = markdown.substring(safeStart, safeEnd)
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.size < 2) return null
            val headers = splitRow(lines[0])
            val sepCells = splitRow(lines[1])
            if (headers.isEmpty() || sepCells.isEmpty() || !sepCells.all { SEPARATOR_CELL.matches(it.trim()) }) return null
            val colCount = maxOf(headers.size, sepCells.size)
            val normalizedHeaders = (0 until colCount).map { headers.getOrNull(it)?.trim().orEmpty() }
            val alignments = (0 until colCount).map { i ->
                val raw = sepCells.getOrNull(i)?.trim().orEmpty()
                when {
                    raw.startsWith(":") && raw.endsWith(":") -> TableAlignment.CENTER
                    raw.endsWith(":") -> TableAlignment.END
                    else -> TableAlignment.START
                }
            }
            val bodyRows = lines.drop(2).map { line ->
                val cells = splitRow(line)
                (0 until colCount).map { cells.getOrNull(it)?.trim().orEmpty() }
            }
            return TableData(normalizedHeaders, alignments, bodyRows)
        }

        /** Splits one GFM table line on unescaped `|` outside inline code spans. */
        internal fun splitRow(line: String): List<String> {
            val trimmed = line.trim().let {
                val start = if (it.startsWith("|")) 1 else 0
                val end = if (it.endsWith("|") && !it.endsWith("\\|") && it.length > start) it.length - 1 else it.length
                it.substring(start, end)
            }
            val cells = ArrayList<String>()
            val current = StringBuilder()
            var inCode = false
            var i = 0
            while (i < trimmed.length) {
                val c = trimmed[i]
                when {
                    c == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '|' && !inCode -> {
                        current.append('|')
                        i += 2
                    }
                    c == '`' -> {
                        inCode = !inCode
                        current.append(c)
                        i++
                    }
                    c == '|' && !inCode -> {
                        cells += current.toString().trim()
                        current.setLength(0)
                        i++
                    }
                    else -> {
                        current.append(c)
                        i++
                    }
                }
            }
            cells += current.toString().trim()
            return cells
        }
    }
}

/**
 * Structured representation of a Mermaid diagram or SVG graphic in agent output, so it can be drawn natively and interactively
 * in Compose (both inline in the chat bubble and in the full-screen zoomable/pannable viewer) with zero network access.
 */
sealed interface DiagramModel {
    val title: String

    enum class Direction { TD, LR, BT, RL }

    enum class NodeShape { RECT, ROUNDED, STADIUM, DIAMOND, CIRCLE, SUBROUTINE }

    data class FlowNode(val id: String, val label: String, val shape: NodeShape = NodeShape.RECT)

    data class FlowEdge(val from: String, val to: String, val label: String? = null, val dotted: Boolean = false)

    data class Flowchart(
        val direction: Direction,
        val nodes: List<FlowNode>,
        val edges: List<FlowEdge>,
        override val title: String = "Flowchart",
    ) : DiagramModel

    data class Participant(val id: String, val label: String)

    data class SeqMessage(val from: String, val to: String, val text: String, val dashed: Boolean = false)

    data class Sequence(
        val participants: List<Participant>,
        val messages: List<SeqMessage>,
        override val title: String = "Sequence diagram",
    ) : DiagramModel

    sealed interface SvgElement {
        data class Rect(val x: Float, val y: Float, val width: Float, val height: Float, val rx: Float = 0f, val fill: String? = null, val stroke: String? = null, val strokeWidth: Float = 1.5f) : SvgElement
        data class Circle(val cx: Float, val cy: Float, val r: Float, val fill: String? = null, val stroke: String? = null, val strokeWidth: Float = 1.5f) : SvgElement
        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val stroke: String? = null, val strokeWidth: Float = 1.5f) : SvgElement
        data class PathShape(val d: String, val fill: String? = null, val stroke: String? = null, val strokeWidth: Float = 1.5f) : SvgElement
        data class TextLabel(val x: Float, val y: Float, val text: String, val fontSize: Float = 14f, val fill: String? = null) : SvgElement
    }

    data class Svg(
        val viewBoxWidth: Float,
        val viewBoxHeight: Float,
        val elements: List<SvgElement>,
        override val title: String = "SVG graphic",
    ) : DiagramModel

    companion object {
        private const val MAX_NODES = 80
        private const val MAX_EDGES = 160
        private const val MAX_SVG_ELEMENTS = 300

        private val FLOW_HEADER = Regex("""^(?:flowchart|graph)\s*(TD|TB|LR|RL|BT)?\b""", RegexOption.IGNORE_CASE)
        private val SEQ_HEADER = Regex("""^sequenceDiagram\b""", RegexOption.IGNORE_CASE)
        private val STATE_HEADER = Regex("""^stateDiagram(?:-v2)?\b""", RegexOption.IGNORE_CASE)
        private val PARTICIPANT_LINE = Regex("""^(?:participant|actor)\s+([A-Za-z0-9_.\-]+)(?:\s+as\s+(.+))?$""", RegexOption.IGNORE_CASE)
        private val SEQ_MSG_LINE = Regex("""^([A-Za-z0-9_.\-]+)\s*(-->>|->>|-->|->|--x|-x|--\)|-\))\s*[+\-]?([A-Za-z0-9_.\-]+)\s*:\s*(.*)$""")
        private val EDGE_PATTERN = Regex(
            """^(.+?)\s*(?:--\s*([^>\-\n]+?)\s*(-->|---)|(-+->|==+>|-->|---|\.-+->)\s*(?:\|([^|]*)\|)?)\s*(.+)$""",
        )

        fun isDiagramFence(lang: String?, code: String): Boolean {
            val cleanLang = lang?.trim()?.lowercase().orEmpty()
            if (cleanLang == "mermaid" || cleanLang == "svg") return true
            val trimmed = code.trimStart()
            return (cleanLang.isEmpty() || cleanLang == "xml" || cleanLang == "html") && trimmed.startsWith("<svg", ignoreCase = true)
        }

        fun parse(lang: String?, code: String): DiagramModel? {
            val cleanLang = lang?.trim()?.lowercase().orEmpty()
            val trimmed = code.trim()
            if (cleanLang == "svg" || trimmed.startsWith("<svg", ignoreCase = true)) {
                return parseSvg(trimmed)
            }
            if (cleanLang == "mermaid") {
                return parseMermaid(trimmed)
            }
            return null
        }

        fun parseMermaid(source: String): DiagramModel? {
            val lines = source.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("%%") }
            if (lines.isEmpty()) return null
            val first = lines.first()
            SEQ_HEADER.find(first)?.let { return parseSequence(lines.drop(1)) }
            STATE_HEADER.find(first)?.let { return parseStateDiagram(lines.drop(1)) }
            val flowMatch = FLOW_HEADER.find(first)
            if (flowMatch != null) {
                val dirToken = flowMatch.groupValues[1].uppercase()
                val dir = when (dirToken) {
                    "LR" -> Direction.LR
                    "RL" -> Direction.RL
                    "BT" -> Direction.BT
                    else -> Direction.TD
                }
                return parseFlowchart(dir, lines.drop(1))
            }
            return null
        }

        private fun parseFlowchart(direction: Direction, lines: List<String>, title: String = "Flowchart"): Flowchart? {
            val nodes = LinkedHashMap<String, FlowNode>()
            val edges = ArrayList<FlowEdge>()

            fun registerNode(raw: String): String? {
                val t = raw.trim().trimEnd(';')
                if (t.isEmpty() || t.startsWith("subgraph", ignoreCase = true) || t.equals("end", ignoreCase = true) ||
                    t.startsWith("classDef", ignoreCase = true) || t.startsWith("class ", ignoreCase = true) ||
                    t.startsWith("style ", ignoreCase = true) || t.startsWith("linkStyle", ignoreCase = true) ||
                    t.startsWith("direction ", ignoreCase = true)
                ) {
                    return null
                }
                val parsed = parseNodeToken(t) ?: return null
                val existing = nodes[parsed.id]
                if (existing == null || (existing.label == existing.id && parsed.label != parsed.id)) {
                    if (nodes.size < MAX_NODES || existing != null) {
                        nodes[parsed.id] = parsed
                    }
                }
                return parsed.id
            }

            for (rawLine in lines) {
                val line = rawLine.trim().trimEnd(';')
                if (line.isEmpty() || line.startsWith("subgraph", ignoreCase = true) || line.equals("end", ignoreCase = true) ||
                    line.startsWith("classDef", ignoreCase = true) || line.startsWith("class ", ignoreCase = true) ||
                    line.startsWith("style ", ignoreCase = true) || line.startsWith("linkStyle", ignoreCase = true) ||
                    line.startsWith("direction ", ignoreCase = true)
                ) {
                    continue
                }
                val edgeMatch = EDGE_PATTERN.find(line)
                if (edgeMatch != null) {
                    val leftRaw = edgeMatch.groupValues[1]
                    val inlineLabel = edgeMatch.groupValues[2].takeIf { it.isNotBlank() }
                    val arrow1 = edgeMatch.groupValues[3]
                    val arrow2 = edgeMatch.groupValues[4]
                    val pipeLabel = edgeMatch.groupValues[5].takeIf { it.isNotBlank() }
                    val rightRaw = edgeMatch.groupValues[6]

                    val fromId = registerNode(leftRaw)
                    val toId = registerNode(rightRaw)
                    if (fromId != null && toId != null && edges.size < MAX_EDGES) {
                        val arrow = arrow1.ifEmpty { arrow2 }
                        val dotted = "." in arrow
                        val label = (inlineLabel ?: pipeLabel)?.trim()?.removeSurrounding("\"")
                        edges += FlowEdge(fromId, toId, label, dotted)
                    }
                } else {
                    registerNode(line)
                }
            }
            if (nodes.isEmpty()) return null
            return Flowchart(direction, nodes.values.toList(), edges, title)
        }

        private fun parseNodeToken(token: String): FlowNode? {
            val t = token.trim()
            if (t.isEmpty()) return null
            val idMatch = Regex("""^([A-Za-z0-9_.\-]+)\s*(.*)$""").find(t) ?: return null
            val id = idMatch.groupValues[1]
            val rest = idMatch.groupValues[2].trim()
            if (rest.isEmpty()) return FlowNode(id, id, NodeShape.RECT)

            val (shape, rawLabel) = when {
                rest.startsWith("([") && rest.endsWith("])") -> NodeShape.STADIUM to rest.substring(2, rest.length - 2)
                rest.startsWith("[[") && rest.endsWith("]]") -> NodeShape.SUBROUTINE to rest.substring(2, rest.length - 2)
                rest.startsWith("((") && rest.endsWith("))") -> NodeShape.CIRCLE to rest.substring(2, rest.length - 2)
                rest.startsWith("[") && rest.endsWith("]") -> NodeShape.RECT to rest.substring(1, rest.length - 1)
                rest.startsWith("(") && rest.endsWith(")") -> NodeShape.ROUNDED to rest.substring(1, rest.length - 1)
                rest.startsWith("{") && rest.endsWith("}") -> NodeShape.DIAMOND to rest.substring(1, rest.length - 1)
                else -> NodeShape.RECT to rest
            }
            val cleanLabel = rawLabel.trim().removeSurrounding("\"").replace("<br/>", "\n").replace("<br>", "\n").ifBlank { id }
            return FlowNode(id, cleanLabel, shape)
        }

        private fun parseStateDiagram(lines: List<String>): Flowchart? {
            val nodes = LinkedHashMap<String, FlowNode>()
            val edges = ArrayList<FlowEdge>()
            val stateTransition = Regex("""^(\[\*\]|[A-Za-z0-9_.\-]+)\s*-->\s*(\[\*\]|[A-Za-z0-9_.\-]+)(?:\s*:\s*(.+))?$""")
            val stateAlias = Regex("""^state\s+"([^"]+)"\s+as\s+([A-Za-z0-9_.\-]+)$""")

            fun ensureNode(rawId: String): String {
                val id = if (rawId == "[*]") "start_end" else rawId
                val label = if (rawId == "[*]") "●" else rawId
                val shape = if (rawId == "[*]") NodeShape.CIRCLE else NodeShape.ROUNDED
                if (id !in nodes && nodes.size < MAX_NODES) {
                    nodes[id] = FlowNode(id, label, shape)
                }
                return id
            }

            for (line in lines) {
                val alias = stateAlias.find(line)
                if (alias != null) {
                    val label = alias.groupValues[1]
                    val id = alias.groupValues[2]
                    if (nodes.size < MAX_NODES) nodes[id] = FlowNode(id, label, NodeShape.ROUNDED)
                    continue
                }
                val match = stateTransition.find(line) ?: continue
                val from = ensureNode(match.groupValues[1])
                val to = ensureNode(match.groupValues[2])
                val label = match.groupValues[3].trim().takeIf { it.isNotEmpty() }
                if (edges.size < MAX_EDGES) edges += FlowEdge(from, to, label)
            }
            if (nodes.isEmpty()) return null
            return Flowchart(Direction.TD, nodes.values.toList(), edges, title = "State diagram")
        }

        private fun parseSequence(lines: List<String>): Sequence? {
            val participants = LinkedHashMap<String, Participant>()
            val messages = ArrayList<SeqMessage>()

            fun ensureParticipant(id: String) {
                if (id !in participants && participants.size < MAX_NODES) {
                    participants[id] = Participant(id, id)
                }
            }

            for (line in lines) {
                val partMatch = PARTICIPANT_LINE.find(line)
                if (partMatch != null) {
                    val id = partMatch.groupValues[1].trim()
                    val label = partMatch.groupValues[2].trim().removeSurrounding("\"").ifBlank { id }
                    if (participants.size < MAX_NODES) participants[id] = Participant(id, label)
                    continue
                }
                val msgMatch = SEQ_MSG_LINE.find(line)
                if (msgMatch != null) {
                    val from = msgMatch.groupValues[1].trim()
                    val arrow = msgMatch.groupValues[2]
                    val to = msgMatch.groupValues[3].trim()
                    val text = msgMatch.groupValues[4].trim()
                    ensureParticipant(from)
                    ensureParticipant(to)
                    if (messages.size < MAX_EDGES) {
                        messages += SeqMessage(from, to, text, dashed = arrow.startsWith("--"))
                    }
                }
            }
            if (participants.isEmpty() && messages.isEmpty()) return null
            return Sequence(participants.values.toList(), messages)
        }

        private val SVG_ROOT = Regex("""<svg\b([^>]*)>([\s\S]*)</svg>""", RegexOption.IGNORE_CASE)
        private val SVG_TAG = Regex("""<(rect|circle|line|path|text)\b([^>]*?)(?:/>|>([\s\S]*?)</\1>)""", RegexOption.IGNORE_CASE)
        private val ATTR = Regex("""([A-Za-z_:][A-Za-z0-9_.\-:]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

        fun parseSvg(source: String): Svg? {
            if (source.length > RichDecoder.MAX_EMBEDDED_CHARS) return null
            val rootMatch = SVG_ROOT.find(source) ?: return null
            val rootAttrs = parseAttrs(rootMatch.groupValues[1])
            val body = rootMatch.groupValues[2]

            val viewBox = rootAttrs["viewBox"]?.trim()?.split(Regex("""[\s,]+"""))?.mapNotNull { it.toFloatOrNull() }
            val width = (viewBox?.getOrNull(2) ?: rootAttrs["width"]?.removeSuffix("px")?.toFloatOrNull() ?: 240f).coerceIn(16f, 4096f)
            val height = (viewBox?.getOrNull(3) ?: rootAttrs["height"]?.removeSuffix("px")?.toFloatOrNull() ?: 160f).coerceIn(16f, 4096f)

            val elements = ArrayList<SvgElement>()
            for (match in SVG_TAG.findAll(body)) {
                if (elements.size >= MAX_SVG_ELEMENTS) break
                val tag = match.groupValues[1].lowercase()
                val attrs = parseAttrs(match.groupValues[2])
                val inner = match.groupValues[3].trim()
                val strokeWidth = attrs["stroke-width"]?.removeSuffix("px")?.toFloatOrNull()?.coerceIn(0.5f, 24f) ?: 1.5f
                when (tag) {
                    "rect" -> {
                        val w = attrs["width"]?.removeSuffix("px")?.toFloatOrNull() ?: continue
                        val h = attrs["height"]?.removeSuffix("px")?.toFloatOrNull() ?: continue
                        if (w > 0f && h > 0f) {
                            elements += SvgElement.Rect(
                                x = attrs["x"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                                y = attrs["y"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                                width = w,
                                height = h,
                                rx = attrs["rx"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                                fill = attrs["fill"],
                                stroke = attrs["stroke"],
                                strokeWidth = strokeWidth,
                            )
                        }
                    }
                    "circle" -> {
                        val r = attrs["r"]?.removeSuffix("px")?.toFloatOrNull() ?: continue
                        if (r > 0f) {
                            elements += SvgElement.Circle(
                                cx = attrs["cx"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                                cy = attrs["cy"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                                r = r,
                                fill = attrs["fill"],
                                stroke = attrs["stroke"],
                                strokeWidth = strokeWidth,
                            )
                        }
                    }
                    "line" -> {
                        elements += SvgElement.Line(
                            x1 = attrs["x1"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                            y1 = attrs["y1"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                            x2 = attrs["x2"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                            y2 = attrs["y2"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                            stroke = attrs["stroke"],
                            strokeWidth = strokeWidth,
                        )
                    }
                    "path" -> {
                        val d = attrs["d"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        elements += SvgElement.PathShape(
                            d = d,
                            fill = attrs["fill"],
                            stroke = attrs["stroke"],
                            strokeWidth = strokeWidth,
                        )
                    }
                    "text" -> {
                        val clean = inner.replace(Regex("<[^>]+>"), "").trim()
                        if (clean.isNotEmpty()) {
                            elements += SvgElement.TextLabel(
                                x = attrs["x"]?.removeSuffix("px")?.toFloatOrNull() ?: 0f,
                                y = attrs["y"]?.removeSuffix("px")?.toFloatOrNull() ?: 16f,
                                text = clean.take(120),
                                fontSize = attrs["font-size"]?.removeSuffix("px")?.toFloatOrNull()?.coerceIn(8f, 64f) ?: 14f,
                                fill = attrs["fill"],
                            )
                        }
                    }
                }
            }
            if (elements.isEmpty()) return null
            return Svg(width, height, elements)
        }

        private fun parseAttrs(raw: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            for (m in ATTR.findAll(raw)) {
                val key = m.groupValues[1]
                val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
                map[key] = value
            }
            return map
        }
    }
}

/**
 * A reference to a resource or attachment in a transcript part or tool result. Kept compact and bounded.
 */
data class ResourceRef(
    val uri: String?,
    val contentType: String?,
    val sizeHint: Long? = null,
    val embeddedData: String? = null,
) {
    val isImage: Boolean
        get() = contentType?.lowercase()?.startsWith("image/") == true

    val isSvg: Boolean
        get() = contentType?.lowercase()?.let { it == "image/svg+xml" || it.endsWith("+svg") } == true

    val isTextLike: Boolean
        get() {
            val mime = contentType?.lowercase()?.substringBefore(';')?.trim() ?: return false
            return mime.startsWith("text/") ||
                mime == "application/json" ||
                mime.endsWith("+json") ||
                mime == "application/xml" ||
                mime.endsWith("+xml") ||
                mime == "application/javascript" ||
                mime == "application/yaml" ||
                mime == "application/x-yaml"
        }

    companion object {
        fun from(part: ResourceResponsePart): ResourceRef = ResourceRef(
            uri = part.uri.takeIf { it.isNotBlank() && it.length <= RichDecoder.MAX_URI },
            contentType = part.contentType,
            sizeHint = part.sizeHint,
        )

        fun from(resource: ToolResultResourceContent): ResourceRef = ResourceRef(
            uri = resource.uri.takeIf { it.isNotBlank() && it.length <= RichDecoder.MAX_URI },
            contentType = resource.contentType,
            sizeHint = resource.sizeHint,
        )

        fun from(embedded: ToolResultEmbeddedResourceContent): ResourceRef = ResourceRef(
            uri = null,
            contentType = embedded.contentType,
            sizeHint = embedded.data.length.toLong(),
            embeddedData = embedded.data.takeIf { it.isNotBlank() && it.length <= RichDecoder.MAX_EMBEDDED_CHARS },
        )
    }
}

/** Decoded payload of an embedded resource or a fetched `ResourceReadResult`, ready to preview inline or open in the viewer. */
sealed interface RichPayload {
    val mimeType: String

    data class ImageBytes(override val mimeType: String, val bytes: ByteArray) : RichPayload {
        override fun equals(other: Any?): Boolean =
            other is ImageBytes && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
    }

    data class SvgContent(override val mimeType: String, val svgText: String, val model: DiagramModel.Svg?) : RichPayload

    data class TextContent(override val mimeType: String, val text: Clipped, val isMarkdown: Boolean = false) : RichPayload

    data class BinaryContent(override val mimeType: String, val bytes: ByteArray) : RichPayload {
        override fun equals(other: Any?): Boolean =
            other is BinaryContent && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
    }
}

/**
 * Safely decodes embedded resource strings and host `ResourceReadResult` payloads into [RichPayload]s within strict memory bounds.
 * Verifies magic bytes for raster images so invalid or dummy test strings (such as `"AAAA"`) are never mistaken for real images.
 */
object RichDecoder {
    /** Maximum characters of an embedded resource kept in memory (~512 KB of base64). */
    const val MAX_EMBEDDED_CHARS = 700_000
    const val MAX_URI = 2_048
    const val MAX_TEXT_PREVIEW = 32_000

    fun decodeEmbedded(ref: ResourceRef): RichPayload? {
        val raw = ref.embeddedData ?: return null
        if (raw.isEmpty() || raw.length > MAX_EMBEDDED_CHARS) return null
        val mime = ref.contentType?.lowercase()?.substringBefore(';')?.trim() ?: "application/octet-stream"
        val clean = stripDataUriPrefix(raw)

        if (mime == "image/svg+xml" || mime.endsWith("+svg")) {
            val svgText = if (clean.trimStart().startsWith("<")) clean else decodeBase64Utf8(clean) ?: return null
            return RichPayload.SvgContent(mime, svgText, DiagramModel.parseSvg(svgText))
        }

        if (mime.startsWith("image/")) {
            val bytes = decodeBase64Bytes(clean) ?: return null
            if (!hasImageMagicBytes(bytes)) return RichPayload.BinaryContent(mime, bytes)
            return RichPayload.ImageBytes(mime, bytes)
        }

        if (ref.isTextLike) {
            val text = decodeBase64Utf8(clean) ?: clean.takeIf { '\u0000' !in it } ?: return null
            val isMd = mime == "text/markdown" || mime == "text/x-markdown"
            return RichPayload.TextContent(mime, Clipped.of(text, MAX_TEXT_PREVIEW), isMarkdown = isMd)
        }

        val bytes = decodeBase64Bytes(clean) ?: clean.encodeToByteArray()
        return RichPayload.BinaryContent(mime, bytes)
    }

    fun decodeResourceResult(result: ResourceReadResult, mimeHint: String? = null, pathHint: String? = null): RichPayload? {
        if (result.data.isEmpty() || result.data.length > MAX_EMBEDDED_CHARS) return null
        val mime = (mimeHint ?: inferMimeFromPath(pathHint) ?: "text/plain").lowercase().substringBefore(';').trim()

        if (mime == "image/svg+xml" || mime.endsWith("+svg")) {
            val svg = when (result.encoding) {
                ContentEncoding.UTF8 -> result.data
                ContentEncoding.BASE64 -> decodeBase64Utf8(result.data) ?: return null
            }
            return RichPayload.SvgContent(mime, svg, DiagramModel.parseSvg(svg))
        }

        if (mime.startsWith("image/")) {
            val bytes = when (result.encoding) {
                ContentEncoding.BASE64 -> decodeBase64Bytes(result.data) ?: return null
                ContentEncoding.UTF8 -> decodeBase64Bytes(result.data) ?: result.data.let { s -> ByteArray(s.length) { i -> s[i].code.toByte() } }
            }
            if (!hasImageMagicBytes(bytes)) return RichPayload.BinaryContent(mime, bytes)
            return RichPayload.ImageBytes(mime, bytes)
        }

        val text = when (result.encoding) {
            ContentEncoding.UTF8 -> result.data.takeIf { '\u0000' !in it }
            ContentEncoding.BASE64 -> decodeBase64Utf8(result.data)
        }
        if (text != null) {
            val isMd = mime == "text/markdown" || pathHint?.lowercase()?.let { it.endsWith(".md") || it.endsWith(".markdown") } == true
            return RichPayload.TextContent(mime, Clipped.of(text, MAX_TEXT_PREVIEW), isMarkdown = isMd)
        }

        val bytes = decodeBase64Bytes(result.data) ?: result.data.let { s -> ByteArray(s.length) { i -> s[i].code.toByte() } }
        return RichPayload.BinaryContent(mime, bytes)
    }

    /** Checks PNG, JPEG, GIF, WebP, and BMP headers so random base64 strings are not treated as decodable bitmaps. */
    fun hasImageMagicBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true
        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true
        // GIF: "GIF8" (47 49 46 38)
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return true
        // WebP: "RIFF" .... "WEBP"
        if (bytes.size >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return true
        }
        // BMP: "BM" (42 4D)
        if (b0 == 0x42 && b1 == 0x4D && bytes.size >= 14) return true
        return false
    }

    fun inferMimeFromPath(path: String?): String? {
        val clean = path?.substringBefore('#')?.substringBefore('?')?.lowercase()?.trim() ?: return null
        return when {
            clean.endsWith(".png") -> "image/png"
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
            clean.endsWith(".webp") -> "image/webp"
            clean.endsWith(".gif") -> "image/gif"
            clean.endsWith(".svg") -> "image/svg+xml"
            clean.endsWith(".bmp") -> "image/bmp"
            clean.endsWith(".md") || clean.endsWith(".markdown") -> "text/markdown"
            clean.endsWith(".json") -> "application/json"
            clean.endsWith(".html") || clean.endsWith(".htm") -> "text/html"
            clean.endsWith(".xml") -> "application/xml"
            clean.endsWith(".pdf") -> "application/pdf"
            else -> null
        }
    }

    private fun stripDataUriPrefix(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("data:", ignoreCase = true)) return trimmed
        val comma = trimmed.indexOf(',')
        return if (comma >= 0) trimmed.substring(comma + 1).trim() else trimmed
    }

    internal fun decodeBase64Bytes(data: String): ByteArray? = base64MimeDecode(data.trim())

    internal fun decodeBase64Utf8(data: String): String? {
        val bytes = decodeBase64Bytes(data) ?: return null
        val decoded = decodeUtf8Strict(bytes) ?: return null
        return decoded.takeIf { '\u0000' !in it }
    }
}
