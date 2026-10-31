package dev.ahpclient.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import dev.ahpclient.app.DiffLoad
import dev.ahpclient.state.ChangesetDiffs
import dev.ahpclient.state.Clipped
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.DiagramModel
import dev.ahpclient.state.DiffResult
import dev.ahpclient.state.HostFileReader
import dev.ahpclient.state.HostFileTarget
import dev.ahpclient.state.ResourceRef
import dev.ahpclient.state.RichDecoder
import dev.ahpclient.state.RichPayload
import dev.ahpclient.state.RichReadResult
import dev.ahpclient.state.TableAlignment
import dev.ahpclient.state.TableData
import dev.ahpclient.state.ToolResultView
import dev.ahpclient.ui.Busy
import dev.ahpclient.ui.ErrorText
import dev.ahpclient.ui.FileDiffScreen
import dev.ahpclient.ui.ScreenScaffold
import dev.ahpclient.ui.copyToClipboard
import java.util.Base64
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Any rich element from the chat that can be opened in the full-screen optimal viewer.
 */
sealed interface RichModalRequest {
    val title: String

    data class Table(override val title: String, val table: TableData) : RichModalRequest

    data class Code(
        override val title: String,
        val language: String?,
        val code: String,
        val startLine: Int? = null,
        val endLine: Int? = null,
    ) : RichModalRequest

    data class Diagram(
        override val title: String,
        val language: String,
        val sourceCode: String,
        val diagram: DiagramModel,
    ) : RichModalRequest

    data class HostFile(val target: HostFileTarget) : RichModalRequest {
        override val title: String
            get() = buildString {
                append(target.displayPath)
                if (target.startLine != null) {
                    append(" : L")
                    append(target.startLine)
                    if (target.endLine != null && target.endLine != target.startLine) {
                        append("-L")
                        append(target.endLine)
                    }
                }
            }
    }

    data class InlineFileEdit(val edit: ToolResultView.FileEdit) : RichModalRequest {
        override val title: String get() = edit.path
    }

    data class Resource(override val title: String, val ref: ResourceRef) : RichModalRequest
}

/**
 * Full-screen optimal viewer for tables, diagrams, code blocks, host files, inline fileEdit diffs, and embedded/remote resources.
 */
@Composable
fun RichViewerModal(
    request: RichModalRequest,
    connection: ConnState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onReadResource: (suspend (String) -> ResourceReadResult)? = null,
    workingDirectories: List<String> = emptyList(),
) {
    BackHandler(onBack = onDismiss)

    if (request is RichModalRequest.InlineFileEdit) {
        var diffLoad by remember(request) { mutableStateOf<DiffLoad>(DiffLoad.Loading) }
        LaunchedEffect(request, onReadResource) {
            if (onReadResource == null) {
                diffLoad = DiffLoad.Loaded(DiffResult.Failed("Not connected to the host."))
            } else {
                diffLoad = DiffLoad.Loading
                val loader = ChangesetDiffs(files = { emptyList() }, read = onReadResource)
                diffLoad = DiffLoad.Loaded(loader.loadSides(request.edit.before, request.edit.after, request.edit.removed))
            }
        }
        Box(modifier = modifier.fillMaxSize().testTag("rich_viewer")) {
            FileDiffScreen(
                path = request.edit.path,
                load = diffLoad,
                connection = connection,
                onBack = onDismiss,
            )
        }
        return
    }

    val subtitle = when (request) {
        is RichModalRequest.Table -> "${request.table.rows.size} rows \u00b7 ${request.table.columnCount} columns"
        is RichModalRequest.Code -> request.language?.takeIf { it.isNotBlank() } ?: "Code"
        is RichModalRequest.Diagram -> "${request.diagram.title} (${request.language})"
        is RichModalRequest.HostFile -> "Host file"
        is RichModalRequest.Resource -> request.ref.contentType ?: "Resource"
        is RichModalRequest.InlineFileEdit -> null
    }

    Surface(modifier = modifier.fillMaxSize().testTag("rich_viewer"), color = MaterialTheme.colorScheme.background) {
        ScreenScaffold(
            title = request.title,
            subtitle = subtitle,
            onBack = onDismiss,
            backLabel = "Back to chat",
            titleTag = "rich_viewer_title",
            large = false,
        ) {
            when (request) {
                is RichModalRequest.Table -> FullScreenTableViewer(request.table)
                is RichModalRequest.Code -> FullScreenCodeViewer(
                    code = request.code,
                    language = request.language,
                    startLine = request.startLine,
                    endLine = request.endLine,
                )
                is RichModalRequest.Diagram -> FullScreenDiagramViewer(
                    diagram = request.diagram,
                    sourceCode = request.sourceCode,
                    language = request.language,
                )
                is RichModalRequest.HostFile -> FullScreenHostFileViewer(
                    target = request.target,
                    onReadResource = onReadResource,
                    workingDirectories = workingDirectories,
                )
                is RichModalRequest.Resource -> FullScreenResourceViewer(
                    title = request.title,
                    ref = request.ref,
                    onReadResource = onReadResource,
                )
                is RichModalRequest.InlineFileEdit -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerActionBar(
    scale: Float,
    onResetScale: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    primaryCopyLabel: String? = null,
    onPrimaryCopy: (() -> Unit)? = null,
    secondaryCopyLabel: String? = null,
    onSecondaryCopy: (() -> Unit)? = null,
    toggleLabel: String? = null,
    onToggle: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (primaryCopyLabel != null && onPrimaryCopy != null) {
            OutlinedButton(
                onClick = onPrimaryCopy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("rich_viewer_copy"),
            ) {
                Text(primaryCopyLabel)
            }
        }
        if (secondaryCopyLabel != null && onSecondaryCopy != null) {
            OutlinedButton(
                onClick = onSecondaryCopy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("rich_viewer_copy_tsv"),
            ) {
                Text(secondaryCopyLabel)
            }
        }
        if (toggleLabel != null && onToggle != null) {
            OutlinedButton(
                onClick = onToggle,
                modifier = Modifier.heightIn(min = 48.dp).testTag("rich_viewer_toggle_mode"),
            ) {
                Text(toggleLabel)
            }
        }
        if (onShare != null) {
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.heightIn(min = 48.dp).testTag("rich_viewer_share"),
            ) {
                Text("Share")
            }
        }
        OutlinedButton(
            onClick = onZoomOut,
            modifier = Modifier.heightIn(min = 48.dp).testTag("rich_viewer_zoom_out"),
        ) {
            Text("Zoom \u2212")
        }
        OutlinedButton(
            onClick = onZoomIn,
            modifier = Modifier.heightIn(min = 48.dp).testTag("rich_viewer_zoom_in"),
        ) {
            Text("Zoom +")
        }
        if (scale != 1f) {
            OutlinedButton(
                onClick = onResetScale,
                modifier = Modifier.heightIn(min = 48.dp).testTag("rich_viewer_zoom_reset"),
            ) {
                Text("${(scale * 100).toInt()}% (Reset)")
            }
        }
    }
}

@Composable
private fun FullScreenTableViewer(table: TableData) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    val hints = remember(table) { table.columnCharHints() }

    Column(modifier = Modifier.fillMaxSize()) {
        ViewerActionBar(
            scale = scale,
            onResetScale = { scale = 1f },
            onZoomIn = { scale = (scale * 1.25f).coerceAtMost(3f) },
            onZoomOut = { scale = (scale / 1.25f).coerceAtLeast(0.5f) },
            primaryCopyLabel = "Copy Markdown",
            onPrimaryCopy = { copyToClipboard(context, "Table Markdown", table.toMarkdown()) },
            secondaryCopyLabel = "Copy TSV",
            onSecondaryCopy = { copyToClipboard(context, "Table TSV", table.toTsv()) },
            onShare = { shareText(context, table.toMarkdown(), "Table") },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 3f)
                    }
                }
                .horizontalScroll(hScroll)
                .verticalScroll(vScroll)
                .padding(12.dp)
                .testTag("rich_viewer_table_content"),
        ) {
            SelectionContainer {
                DataTableGrid(table = table, columnHints = hints, scale = scale)
            }
        }
    }
}

@Composable
internal fun DataTableGrid(
    table: TableData,
    columnHints: List<Int>,
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val evenRowBg = MaterialTheme.colorScheme.surfaceContainerLow
    val oddRowBg = MaterialTheme.colorScheme.surface
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Surface(
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.border(1.dp, borderColor, RoundedCornerShape(8.dp)),
    ) {
        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(headerBg),
            ) {
                for (col in 0 until table.columnCount) {
                    if (col > 0) {
                        VerticalDivider(modifier = Modifier.fillMaxHeight(), color = borderColor)
                    }
                    val cellText = table.headers.getOrElse(col) { "" }
                    val formatted = remember(cellText, linkColor, codeBg, uriHandler) {
                        formatCellMarkdown(cellText, linkColor, codeBg) { uriHandler.openUri(it) }
                    }
                    val align = table.alignments.getOrElse(col) { TableAlignment.START }
                    val colWidth = columnWidthDp(columnHints.getOrElse(col) { 12 }, scale)
                    Box(
                        modifier = Modifier
                            .width(colWidth)
                            .fillMaxHeight()
                            .padding(horizontal = (10 * scale).dp, vertical = (8 * scale).dp),
                        contentAlignment = cellBoxAlignment(align),
                    ) {
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = (14f * scale).sp,
                                lineHeight = (20f * scale).sp,
                            ),
                            textAlign = cellTextAlign(align),
                        )
                    }
                }
            }
            // Body rows
            table.rows.forEachIndexed { rowIndex, row ->
                HorizontalDivider(color = borderColor)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(if (rowIndex % 2 == 0) oddRowBg else evenRowBg),
                ) {
                    for (col in 0 until table.columnCount) {
                        if (col > 0) {
                            VerticalDivider(modifier = Modifier.fillMaxHeight(), color = borderColor)
                        }
                        val cellText = row.getOrElse(col) { "" }
                        val formatted = remember(cellText, linkColor, codeBg, uriHandler) {
                            formatCellMarkdown(cellText, linkColor, codeBg) { uriHandler.openUri(it) }
                        }
                        val align = table.alignments.getOrElse(col) { TableAlignment.START }
                        val colWidth = columnWidthDp(columnHints.getOrElse(col) { 12 }, scale)
                        Box(
                            modifier = Modifier
                                .width(colWidth)
                                .fillMaxHeight()
                                .padding(horizontal = (10 * scale).dp, vertical = (8 * scale).dp),
                            contentAlignment = cellBoxAlignment(align),
                        ) {
                            Text(
                                text = formatted,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = (14f * scale).sp,
                                    lineHeight = (20f * scale).sp,
                                ),
                                textAlign = cellTextAlign(align),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun columnWidthDp(charHint: Int, scale: Float = 1f): Dp {
    // Give short columns compact width and long columns generous wrapping width up to 320dp (scaled) so nothing is ever cut off.
    val baseDp = (charHint * 8.5f + 24f).coerceIn(88f, 320f)
    return (baseDp * scale).dp
}

internal fun cellTextAlign(alignment: TableAlignment): TextAlign = when (alignment) {
    TableAlignment.START -> TextAlign.Start
    TableAlignment.CENTER -> TextAlign.Center
    TableAlignment.END -> TextAlign.End
}

internal fun cellBoxAlignment(alignment: TableAlignment): Alignment = when (alignment) {
    TableAlignment.START -> Alignment.TopStart
    TableAlignment.CENTER -> Alignment.TopCenter
    TableAlignment.END -> Alignment.TopEnd
}

@Composable
private fun FullScreenCodeViewer(
    code: String,
    language: String?,
    startLine: Int? = null,
    endLine: Int? = null,
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    val lines = remember(code) { code.replace("\r\n", "\n").replace('\r', '\n').split('\n') }

    Column(modifier = Modifier.fillMaxSize()) {
        ViewerActionBar(
            scale = scale,
            onResetScale = { scale = 1f },
            onZoomIn = { scale = (scale * 1.25f).coerceAtMost(3f) },
            onZoomOut = { scale = (scale / 1.25f).coerceAtLeast(0.5f) },
            primaryCopyLabel = "Copy code",
            onPrimaryCopy = { copyToClipboard(context, language ?: "Code", code) },
            onShare = { shareText(context, code, language ?: "Code") },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 3f)
                    }
                }
                .horizontalScroll(hScroll)
                .verticalScroll(vScroll)
                .padding(12.dp)
                .testTag("rich_viewer_code_content"),
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy((2 * scale).dp)) {
                    lines.forEachIndexed { idx, lineText ->
                        val lineNum = idx + 1
                        val highlighted = startLine != null && lineNum >= startLine && (endLine == null || lineNum <= endLine) && (endLine != null || lineNum == startLine)
                        val rowBg = if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = lineNum.toString().padStart(4, ' '),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = (12f * scale).sp,
                                    lineHeight = (18f * scale).sp,
                                ),
                                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = lineText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = (13f * scale).sp,
                                    lineHeight = (18f * scale).sp,
                                ),
                                softWrap = false,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenDiagramViewer(
    diagram: DiagramModel,
    sourceCode: String,
    language: String,
) {
    val context = LocalContext.current
    var showSource by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Column(modifier = Modifier.fillMaxSize()) {
        ViewerActionBar(
            scale = scale,
            onResetScale = {
                scale = 1f
                offset = Offset.Zero
            },
            onZoomIn = { scale = (scale * 1.25f).coerceAtMost(4f) },
            onZoomOut = { scale = (scale / 1.25f).coerceAtLeast(0.4f) },
            primaryCopyLabel = "Copy source",
            onPrimaryCopy = { copyToClipboard(context, language, sourceCode) },
            toggleLabel = if (showSource) "Show diagram" else "Show source",
            onToggle = { showSource = !showSource },
            onShare = { shareText(context, sourceCode, "Diagram ($language)") },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (showSource) {
            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()
            SelectionContainer {
                Text(
                    text = sourceCode,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (13f * scale).sp,
                        lineHeight = (19f * scale).sp,
                    ),
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(hScroll)
                        .verticalScroll(vScroll)
                        .padding(16.dp)
                        .testTag("rich_viewer_diagram_source"),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.4f, 4f)
                            offset += pan
                        }
                    }
                    .padding(12.dp)
                    .testTag("rich_viewer_diagram_canvas"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                ) {
                    DiagramCanvas(diagram = diagram)
                }
            }
        }
    }
}

@Composable
private fun FullScreenHostFileViewer(
    target: HostFileTarget,
    onReadResource: (suspend (String) -> ResourceReadResult)?,
    workingDirectories: List<String>,
) {
    var result by remember(target) { mutableStateOf<RichReadResult?>(null) }
    LaunchedEffect(target, onReadResource, workingDirectories) {
        if (onReadResource == null) {
            result = RichReadResult.Failed("Not connected to the host.")
        } else {
            result = null
            val reader = HostFileReader(read = onReadResource, workingDirectories = { workingDirectories })
            result = reader.readTarget(target)
        }
    }

    when (val current = result) {
        null -> Busy("Reading ${target.displayPath}\u2026", Modifier.padding(16.dp).testTag("rich_viewer_loading"))
        RichReadResult.TooLarge -> ErrorText("This file is too large to display on the phone.", Modifier.padding(16.dp).testTag("rich_viewer_error"))
        is RichReadResult.Failed -> ErrorText(current.reason, Modifier.padding(16.dp).testTag("rich_viewer_error"))
        is RichReadResult.Ready -> PayloadViewer(
            title = current.title,
            payload = current.payload,
            startLine = current.startLine,
            endLine = current.endLine,
        )
    }
}

@Composable
private fun FullScreenResourceViewer(
    title: String,
    ref: ResourceRef,
    onReadResource: (suspend (String) -> ResourceReadResult)?,
) {
    var result by remember(ref) { mutableStateOf<RichReadResult?>(null) }
    LaunchedEffect(ref, onReadResource) {
        if (ref.embeddedData != null) {
            val payload = RichDecoder.decodeEmbedded(ref)
            result = if (payload != null) RichReadResult.Ready(title, payload) else RichReadResult.TooLarge
        } else if (onReadResource == null) {
            result = RichReadResult.Failed("Not connected to the host.")
        } else {
            result = null
            val reader = HostFileReader(read = onReadResource)
            result = reader.readResourceRef(ref, title)
        }
    }

    when (val current = result) {
        null -> Busy("Loading resource\u2026", Modifier.padding(16.dp).testTag("rich_viewer_loading"))
        RichReadResult.TooLarge -> ErrorText("This attachment is too large to display on the phone.", Modifier.padding(16.dp).testTag("rich_viewer_error"))
        is RichReadResult.Failed -> ErrorText(current.reason, Modifier.padding(16.dp).testTag("rich_viewer_error"))
        is RichReadResult.Ready -> PayloadViewer(
            title = current.title,
            payload = current.payload,
            startLine = current.startLine,
            endLine = current.endLine,
        )
    }
}

@Composable
private fun PayloadViewer(
    title: String,
    payload: RichPayload,
    startLine: Int? = null,
    endLine: Int? = null,
) {
    val context = LocalContext.current
    when (payload) {
        is RichPayload.TextContent -> FullScreenCodeViewer(
            code = payload.text.text,
            language = payload.mimeType,
            startLine = startLine,
            endLine = endLine,
        )
        is RichPayload.SvgContent -> {
            val diagram = payload.model
            if (diagram != null) {
                FullScreenDiagramViewer(diagram = diagram, sourceCode = payload.svgText, language = "svg")
            } else {
                FullScreenCodeViewer(code = payload.svgText, language = "svg")
            }
        }
        is RichPayload.ImageBytes -> {
            val bitmap = remember(payload.bytes) {
                try {
                    BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.size)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            Column(modifier = Modifier.fillMaxSize()) {
                ViewerActionBar(
                    scale = scale,
                    onResetScale = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                    onZoomIn = { scale = (scale * 1.25f).coerceAtMost(5f) },
                    onZoomOut = { scale = (scale / 1.25f).coerceAtLeast(0.5f) },
                    primaryCopyLabel = "Copy Base64",
                    onPrimaryCopy = {
                        copyToClipboard(context, title, Base64.getEncoder().encodeToString(payload.bytes))
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (bitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                                    offset += pan
                                }
                            }
                            .padding(16.dp)
                            .testTag("rich_viewer_image"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y,
                                ),
                        )
                    }
                } else {
                    ErrorText("Could not decode image (${payload.mimeType}, ${payload.bytes.size} bytes).", Modifier.padding(16.dp))
                }
            }
        }
        is RichPayload.BinaryContent -> {
            val b64 = remember(payload.bytes) {
                if (payload.bytes.isNotEmpty() && payload.bytes.size <= 256 * 1024) {
                    Base64.getEncoder().encodeToString(payload.bytes)
                } else {
                    null
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).testTag("rich_viewer_binary"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Binary attachment (${payload.mimeType}, ${payload.bytes.size} bytes)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "This binary data cannot be rendered as text or an image directly in chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (b64 != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyToClipboard(context, title, b64) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("rich_viewer_copy"),
                        ) {
                            Text("Copy Base64")
                        }
                        OutlinedButton(
                            onClick = { shareText(context, b64, title) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("rich_viewer_share"),
                        ) {
                            Text("Share")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders a [DiagramModel] (Mermaid flowchart, sequence diagram, state diagram, or SVG) using native Compose Canvas.
 */
@Composable
fun DiagramCanvas(
    diagram: DiagramModel,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline

    when (diagram) {
        is DiagramModel.Flowchart -> FlowchartCanvas(
            diagram = diagram,
            textMeasurer = textMeasurer,
            nodeFill = primaryContainer,
            nodeStroke = primaryColor,
            nodeText = onPrimaryContainer,
            edgeColor = outline,
            edgeLabelBg = surfaceContainer,
            edgeLabelText = onSurface,
            modifier = modifier,
        )
        is DiagramModel.Sequence -> SequenceCanvas(
            diagram = diagram,
            textMeasurer = textMeasurer,
            boxFill = primaryContainer,
            boxStroke = primaryColor,
            boxText = onPrimaryContainer,
            lineColor = outline,
            messageText = onSurface,
            modifier = modifier,
        )
        is DiagramModel.Svg -> SvgCanvas(
            diagram = diagram,
            textMeasurer = textMeasurer,
            defaultStroke = onSurface,
            defaultText = onSurface,
            modifier = modifier,
        )
    }
}

@Composable
private fun FlowchartCanvas(
    diagram: DiagramModel.Flowchart,
    textMeasurer: TextMeasurer,
    nodeFill: Color,
    nodeStroke: Color,
    nodeText: Color,
    edgeColor: Color,
    edgeLabelBg: Color,
    edgeLabelText: Color,
    modifier: Modifier = Modifier,
) {
    val layout = remember(diagram) { computeFlowchartLayout(diagram) }
    val widthDp = (layout.widthPx / 2.2f).coerceAtLeast(220f).dp
    val heightDp = (layout.heightPx / 2.2f).coerceAtLeast(120f).dp

    Canvas(
        modifier = modifier
            .size(width = widthDp, height = heightDp)
            .semantics { contentDescription = "${diagram.title} with ${diagram.nodes.size} nodes" },
    ) {
        val scaleX = size.width / layout.widthPx.coerceAtLeast(1f)
        val scaleY = size.height / layout.heightPx.coerceAtLeast(1f)
        val s = min(scaleX, scaleY)

        for (edge in diagram.edges) {
            val fromRect = layout.positions[edge.from] ?: continue
            val toRect = layout.positions[edge.to] ?: continue
            val start = Offset((fromRect.cx) * s, (fromRect.cy) * s)
            val end = Offset((toRect.cx) * s, (toRect.cy) * s)
            val clippedEnd = clipToNodeBorder(start, end, toRect, s)
            val clippedStart = clipToNodeBorder(end, start, fromRect, s)

            val pathEffect = if (edge.dotted) PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) else null
            drawLine(
                color = edgeColor,
                start = clippedStart,
                end = clippedEnd,
                strokeWidth = 2.5f,
                pathEffect = pathEffect,
            )
            drawArrowHead(edgeColor, clippedStart, clippedEnd, sizePx = 12f)

            val label = edge.label
            if (!label.isNullOrBlank()) {
                val mid = Offset((clippedStart.x + clippedEnd.x) / 2f, (clippedStart.y + clippedEnd.y) / 2f)
                val measured = textMeasurer.measure(
                    text = label,
                    style = TextStyle(color = edgeLabelText, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
                val padX = 8f
                val padY = 4f
                val pillW = measured.size.width + padX * 2
                val pillH = measured.size.height + padY * 2
                drawRoundRect(
                    color = edgeLabelBg,
                    topLeft = Offset(mid.x - pillW / 2f, mid.y - pillH / 2f),
                    size = Size(pillW, pillH),
                    cornerRadius = CornerRadius(6f, 6f),
                )
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(mid.x - measured.size.width / 2f, mid.y - measured.size.height / 2f),
                )
            }
        }

        for (node in diagram.nodes) {
            val pos = layout.positions[node.id] ?: continue
            val left = (pos.cx - pos.w / 2f) * s
            val top = (pos.cy - pos.h / 2f) * s
            val w = pos.w * s
            val h = pos.h * s

            when (node.shape) {
                DiagramModel.NodeShape.DIAMOND -> {
                    val path = Path().apply {
                        moveTo(left + w / 2f, top)
                        lineTo(left + w, top + h / 2f)
                        lineTo(left + w / 2f, top + h)
                        lineTo(left, top + h / 2f)
                        close()
                    }
                    drawPath(path, color = nodeFill, style = Fill)
                    drawPath(path, color = nodeStroke, style = Stroke(width = 2.5f))
                }
                DiagramModel.NodeShape.CIRCLE -> {
                    drawOval(color = nodeFill, topLeft = Offset(left, top), size = Size(w, h))
                    drawOval(color = nodeStroke, topLeft = Offset(left, top), size = Size(w, h), style = Stroke(width = 2.5f))
                }
                DiagramModel.NodeShape.ROUNDED, DiagramModel.NodeShape.STADIUM -> {
                    drawRoundRect(color = nodeFill, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(h / 2f, h / 2f))
                    drawRoundRect(color = nodeStroke, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(h / 2f, h / 2f), style = Stroke(width = 2.5f))
                }
                DiagramModel.NodeShape.SUBROUTINE -> {
                    drawRoundRect(color = nodeFill, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(8f, 8f))
                    drawRoundRect(color = nodeStroke, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(8f, 8f), style = Stroke(width = 2.5f))
                    drawLine(nodeStroke, Offset(left + 10f, top), Offset(left + 10f, top + h), strokeWidth = 2f)
                    drawLine(nodeStroke, Offset(left + w - 10f, top), Offset(left + w - 10f, top + h), strokeWidth = 2f)
                }
                DiagramModel.NodeShape.RECT -> {
                    drawRoundRect(color = nodeFill, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(10f, 10f))
                    drawRoundRect(color = nodeStroke, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(10f, 10f), style = Stroke(width = 2.5f))
                }
            }

            val measured = textMeasurer.measure(
                text = node.label,
                style = TextStyle(color = nodeText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                constraints = Constraints(maxWidth = (w - 12f).toInt().coerceAtLeast(20)),
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    left + (w - measured.size.width) / 2f,
                    top + (h - measured.size.height) / 2f,
                ),
            )
        }
    }
}

private class NodeBox(val cx: Float, val cy: Float, val w: Float, val h: Float)

private class FlowchartLayout(
    val positions: Map<String, NodeBox>,
    val widthPx: Float,
    val heightPx: Float,
)

private fun computeFlowchartLayout(diagram: DiagramModel.Flowchart): FlowchartLayout {
    val ranks = LinkedHashMap<String, Int>()
    diagram.nodes.forEach { ranks[it.id] = 0 }
    val maxRankPass = diagram.nodes.size.coerceAtMost(20)
    repeat(maxRankPass) {
        for (edge in diagram.edges) {
            if (edge.from == edge.to) continue
            val u = ranks[edge.from] ?: 0
            val v = ranks[edge.to] ?: 0
            if (v <= u && u < maxRankPass) {
                ranks[edge.to] = u + 1
            }
        }
    }
    val layers = LinkedHashMap<Int, MutableList<DiagramModel.FlowNode>>()
    for (node in diagram.nodes) {
        val r = ranks[node.id] ?: 0
        layers.getOrPut(r) { ArrayList() }.add(node)
    }

    val nodeGap = 48f
    val layerGap = 96f
    val pad = 36f
    val positions = LinkedHashMap<String, NodeBox>()

    val sortedLayers = layers.entries.sortedBy { it.key }.map { it.value }
    val horizontal = diagram.direction == DiagramModel.Direction.LR || diagram.direction == DiagramModel.Direction.RL
    if (horizontal) {
        var curX = pad
        var maxH = 200f
        for (layer in sortedLayers) {
            var layerW = 140f
            val nodeSizes = layer.map { n ->
                val w = (n.label.length * 14f + 44f).coerceIn(120f, 280f)
                val h = if (n.shape == DiagramModel.NodeShape.DIAMOND) 84f else 64f
                layerW = max(layerW, w)
                w to h
            }
            val totalH = nodeSizes.sumOf { it.second.toDouble() }.toFloat() + (layer.size - 1).coerceAtLeast(0) * nodeGap
            maxH = max(maxH, totalH + pad * 2)
            var curY = pad
            layer.forEachIndexed { idx, node ->
                val (w, h) = nodeSizes[idx]
                positions[node.id] = NodeBox(cx = curX + layerW / 2f, cy = curY + h / 2f, w = w, h = h)
                curY += h + nodeGap
            }
            curX += layerW + layerGap
        }
        val totalW = (curX - layerGap + pad).coerceAtLeast(260f)
        for (layer in sortedLayers) {
            val boxes = layer.mapNotNull { positions[it.id] }
            if (boxes.isEmpty()) continue
            val top = boxes.minOf { it.cy - it.h / 2f }
            val bottom = boxes.maxOf { it.cy + it.h / 2f }
            val shift = (maxH - (bottom - top)) / 2f - top
            for (node in layer) {
                val b = positions[node.id] ?: continue
                positions[node.id] = NodeBox(b.cx, b.cy + shift, b.w, b.h)
            }
        }
        return FlowchartLayout(positions, totalW, maxH)
    } else {
        var curY = pad
        var maxW = 260f
        for (layer in sortedLayers) {
            var layerH = 64f
            val nodeSizes = layer.map { n ->
                val w = (n.label.length * 14f + 44f).coerceIn(120f, 280f)
                val h = if (n.shape == DiagramModel.NodeShape.DIAMOND) 84f else 64f
                layerH = max(layerH, h)
                w to h
            }
            val totalW = nodeSizes.sumOf { it.first.toDouble() }.toFloat() + (layer.size - 1).coerceAtLeast(0) * nodeGap
            maxW = max(maxW, totalW + pad * 2)
            var curX = pad
            layer.forEachIndexed { idx, node ->
                val (w, h) = nodeSizes[idx]
                positions[node.id] = NodeBox(cx = curX + w / 2f, cy = curY + layerH / 2f, w = w, h = h)
                curX += w + nodeGap
            }
            curY += layerH + layerGap
        }
        val totalH = (curY - layerGap + pad).coerceAtLeast(180f)
        for (layer in sortedLayers) {
            val boxes = layer.mapNotNull { positions[it.id] }
            if (boxes.isEmpty()) continue
            val left = boxes.minOf { it.cx - it.w / 2f }
            val right = boxes.maxOf { it.cx + it.w / 2f }
            val shift = (maxW - (right - left)) / 2f - left
            for (node in layer) {
                val b = positions[node.id] ?: continue
                positions[node.id] = NodeBox(b.cx + shift, b.cy, b.w, b.h)
            }
        }
        return FlowchartLayout(positions, maxW, totalH)
    }
}

private fun clipToNodeBorder(from: Offset, to: Offset, targetBox: NodeBox, scale: Float): Offset {
    val dx = to.x - from.x
    val dy = to.y - from.y
    if (dx == 0f && dy == 0f) return to
    val hw = (targetBox.w * scale) / 2f + 4f
    val hh = (targetBox.h * scale) / 2f + 4f
    val tx = if (dx != 0f) hw / kotlin.math.abs(dx) else Float.MAX_VALUE
    val ty = if (dy != 0f) hh / kotlin.math.abs(dy) else Float.MAX_VALUE
    val t = min(tx, ty).coerceIn(0f, 1f)
    return Offset(to.x - dx * t, to.y - dy * t)
}

private fun DrawScope.drawArrowHead(color: Color, from: Offset, to: Offset, sizePx: Float) {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val spread = 0.45f
    val p1 = Offset(
        to.x - sizePx * cos(angle - spread),
        to.y - sizePx * sin(angle - spread),
    )
    val p2 = Offset(
        to.x - sizePx * cos(angle + spread),
        to.y - sizePx * sin(angle + spread),
    )
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        close()
    }
    drawPath(path, color = color, style = Fill)
}

@Composable
private fun SequenceCanvas(
    diagram: DiagramModel.Sequence,
    textMeasurer: TextMeasurer,
    boxFill: Color,
    boxStroke: Color,
    boxText: Color,
    lineColor: Color,
    messageText: Color,
    modifier: Modifier = Modifier,
) {
    val colSpacing = 180f
    val rowSpacing = 62f
    val boxW = 140f
    val boxH = 50f
    val pad = 32f

    val totalW = (pad * 2 + (diagram.participants.size - 1).coerceAtLeast(0) * colSpacing + boxW).coerceAtLeast(260f)
    val totalH = (pad * 2 + boxH + diagram.messages.size * rowSpacing + 36f).coerceAtLeast(180f)

    Canvas(
        modifier = modifier
            .size(width = (totalW / 2f).dp, height = (totalH / 2f).dp)
            .semantics { contentDescription = "Sequence diagram with ${diagram.participants.size} participants" },
    ) {
        val s = min(size.width / totalW, size.height / totalH)
        val xs = LinkedHashMap<String, Float>()
        diagram.participants.forEachIndexed { idx, p ->
            val cx = (pad + boxW / 2f + idx * colSpacing) * s
            xs[p.id] = cx
            val left = cx - (boxW * s) / 2f
            val top = pad * s
            val w = boxW * s
            val h = boxH * s
            drawRoundRect(boxFill, Offset(left, top), Size(w, h), CornerRadius(10f, 10f))
            drawRoundRect(boxStroke, Offset(left, top), Size(w, h), CornerRadius(10f, 10f), style = Stroke(width = 2.5f))

            val measured = textMeasurer.measure(
                text = p.label,
                style = TextStyle(color = boxText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                constraints = Constraints(maxWidth = (w - 8f).toInt().coerceAtLeast(20)),
            )
            drawText(textLayoutResult = measured, topLeft = Offset(left + (w - measured.size.width) / 2f, top + (h - measured.size.height) / 2f))

            drawLine(
                color = lineColor,
                start = Offset(cx, top + h),
                end = Offset(cx, (totalH - pad) * s),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
            )
        }

        diagram.messages.forEachIndexed { idx, msg ->
            val y = (pad + boxH + 38f + idx * rowSpacing) * s
            val x1 = xs[msg.from] ?: return@forEachIndexed
            val x2 = xs[msg.to] ?: return@forEachIndexed
            val pathEffect = if (msg.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) else null

            if (msg.from == msg.to) {
                val loopW = 36f * s
                val loopH = 18f * s
                drawLine(lineColor, Offset(x1, y - loopH / 2f), Offset(x1 + loopW, y - loopH / 2f), strokeWidth = 2.5f, pathEffect = pathEffect)
                drawLine(lineColor, Offset(x1 + loopW, y - loopH / 2f), Offset(x1 + loopW, y + loopH / 2f), strokeWidth = 2.5f, pathEffect = pathEffect)
                drawLine(lineColor, Offset(x1 + loopW, y + loopH / 2f), Offset(x1, y + loopH / 2f), strokeWidth = 2.5f, pathEffect = pathEffect)
                drawArrowHead(lineColor, Offset(x1 + loopW, y + loopH / 2f), Offset(x1, y + loopH / 2f), 10f)
            } else {
                drawLine(lineColor, Offset(x1, y), Offset(x2, y), strokeWidth = 2.5f, pathEffect = pathEffect)
                drawArrowHead(lineColor, Offset(x1, y), Offset(x2, y), 11f)
            }

            if (msg.text.isNotBlank()) {
                val measured = textMeasurer.measure(
                    text = msg.text,
                    style = TextStyle(color = messageText, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
                val midX = if (msg.from == msg.to) x1 + 24f * s else (x1 + x2) / 2f
                drawText(textLayoutResult = measured, topLeft = Offset(midX - measured.size.width / 2f, y - measured.size.height - 4f))
            }
        }
    }
}

@Composable
private fun SvgCanvas(
    diagram: DiagramModel.Svg,
    textMeasurer: TextMeasurer,
    defaultStroke: Color,
    defaultText: Color,
    modifier: Modifier = Modifier,
) {
    val wDp = diagram.viewBoxWidth.coerceIn(120f, 480f).dp
    val hDp = diagram.viewBoxHeight.coerceIn(80f, 360f).dp

    Canvas(
        modifier = modifier
            .size(width = wDp, height = hDp)
            .semantics { contentDescription = "SVG graphic" },
    ) {
        val sx = size.width / diagram.viewBoxWidth.coerceAtLeast(1f)
        val sy = size.height / diagram.viewBoxHeight.coerceAtLeast(1f)
        val s = min(sx, sy)

        for (el in diagram.elements) {
            when (el) {
                is DiagramModel.SvgElement.Rect -> {
                    val topLeft = Offset(el.x * s, el.y * s)
                    val rectSize = Size(el.width * s, el.height * s)
                    val corner = CornerRadius(el.rx * s, el.rx * s)
                    val fillColor = parseSvgColor(el.fill)
                    val strokeColor = parseSvgColor(el.stroke) ?: if (fillColor == null) defaultStroke else null
                    fillColor?.let { drawRoundRect(it, topLeft, rectSize, corner, style = Fill) }
                    strokeColor?.let { drawRoundRect(it, topLeft, rectSize, corner, style = Stroke(width = (el.strokeWidth * s).coerceAtLeast(1f))) }
                }
                is DiagramModel.SvgElement.Circle -> {
                    val center = Offset(el.cx * s, el.cy * s)
                    val r = el.r * s
                    val fillColor = parseSvgColor(el.fill)
                    val strokeColor = parseSvgColor(el.stroke) ?: if (fillColor == null) defaultStroke else null
                    fillColor?.let { drawCircle(it, radius = r, center = center, style = Fill) }
                    strokeColor?.let { drawCircle(it, radius = r, center = center, style = Stroke(width = (el.strokeWidth * s).coerceAtLeast(1f))) }
                }
                is DiagramModel.SvgElement.Line -> {
                    val c = parseSvgColor(el.stroke) ?: defaultStroke
                    drawLine(c, Offset(el.x1 * s, el.y1 * s), Offset(el.x2 * s, el.y2 * s), strokeWidth = (el.strokeWidth * s).coerceAtLeast(1.5f))
                }
                is DiagramModel.SvgElement.PathShape -> {
                    val parsed = try {
                        PathParser().parsePathString(el.d).toPath()
                    } catch (_: Exception) {
                        null
                    }
                    if (parsed != null) {
                        parseSvgColor(el.fill)?.let { drawPath(parsed, it, style = Fill) }
                        val c = parseSvgColor(el.stroke) ?: defaultStroke
                        drawPath(parsed, c, style = Stroke(width = (el.strokeWidth * s).coerceAtLeast(1.5f)))
                    }
                }
                is DiagramModel.SvgElement.TextLabel -> {
                    val c = parseSvgColor(el.fill) ?: defaultText
                    val measured = textMeasurer.measure(
                        text = el.text,
                        style = TextStyle(color = c, fontSize = (el.fontSize * s).coerceIn(9f, 28f).sp),
                    )
                    drawText(textLayoutResult = measured, topLeft = Offset(el.x * s, el.y * s - measured.size.height * 0.8f))
                }
            }
        }
    }
}

private fun parseSvgColor(raw: String?): Color? {
    val c = raw?.trim()?.lowercase() ?: return null
    if (c.isEmpty() || c == "none" || c == "transparent") return null
    if (c.startsWith("#")) {
        val hex = c.substring(1)
        return when (hex.length) {
            3 -> {
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                Color((r shl 4) or r, (g shl 4) or g, (b shl 4) or b)
            }
            6 -> hex.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
            8 -> hex.toLongOrNull(16)?.let { Color(it) }
            else -> null
        }
    }
    return when (c) {
        "black" -> Color(0xFF000000)
        "white" -> Color(0xFFFFFFFF)
        "red" -> Color(0xFFEF4444)
        "green" -> Color(0xFF22C55E)
        "blue" -> Color(0xFF3B82F6)
        "yellow" -> Color(0xFFEAB308)
        "orange" -> Color(0xFFF97316)
        "purple", "violet" -> Color(0xFFA855F7)
        "cyan", "teal" -> Color(0xFF14B8A6)
        "gray", "grey" -> Color(0xFF6B7280)
        else -> null
    }
}

/**
 * Renders an inline preview for an embedded or referenced resource (image, SVG, text, or binary) with a button to open the full viewer.
 */
@Composable
fun InlineResourcePreview(
    label: String,
    ref: ResourceRef,
    onOpenRich: ((RichModalRequest) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val embeddedPayload = remember(ref) {
        if (ref.embeddedData != null) RichDecoder.decodeEmbedded(ref) else null
    }
    val bitmap: ImageBitmap? = remember(embeddedPayload) {
        val img = embeddedPayload as? RichPayload.ImageBytes ?: return@remember null
        try {
            BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            bitmap != null -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .heightIn(min = 48.dp, max = 200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .let { mod ->
                            if (onOpenRich != null) {
                                mod.clickable(
                                    onClickLabel = "Open image in full viewer",
                                    role = Role.Button,
                                ) { onOpenRich(RichModalRequest.Resource(label, ref)) }
                            } else {
                                mod
                            }
                        }
                        .testTag("attachment_image_preview"),
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            embeddedPayload is RichPayload.SvgContent && embeddedPayload.model != null -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(4.dp)
                        .testTag("attachment_svg_preview"),
                ) {
                    DiagramCanvas(diagram = embeddedPayload.model!!, modifier = Modifier.padding(8.dp))
                }
            }
            embeddedPayload is RichPayload.TextContent -> {
                MonoBlock(Clipped.of(embeddedPayload.text.text, 600), Modifier.testTag("attachment_text_preview"))
            }
        }
        if (onOpenRich != null) {
            OutlinedButton(
                onClick = { onOpenRich(RichModalRequest.Resource(label, ref)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("attachment_open"),
            ) {
                Text("Open in viewer")
            }
        }
    }
}

internal fun shareText(context: Context, text: String, title: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, title))
    } catch (_: Exception) {
        // Ignore if no activity can handle share on test devices
    }
}
