package dev.ahpclient.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.ahpclient.state.DiagramModel
import dev.ahpclient.state.HostFileTarget
import dev.ahpclient.state.MarkdownLinks
import dev.ahpclient.state.TableData
import dev.ahpclient.ui.rememberPlatformUiActions

/**
 * Agent output as formatted text. The text is untrusted, so this renders safely: no external image loader is installed,
 * links that open nothing on a phone are plain text unless [allowHostFiles] enables in-app host file previews, and every
 * external link tap goes to [onOpenLink] so the caller can confirm with the user first.
 *
 * Tables, code blocks, and Mermaid/SVG diagrams are rendered with rich inline cards (scrollable, never truncating table cell text)
 * and offer one-tap expansion into [onOpenRich] for full-screen zoomable viewing and export.
 */
@Composable
fun MarkdownPart(
    text: String,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowHostFiles: Boolean = false,
    onOpenRich: ((RichModalRequest) -> Unit)? = null,
) {
    val openable = remember(text, allowHostFiles) {
        MarkdownLinks.keepOpenable(text, allowHostFiles = allowHostFiles)
    }
    val state = rememberMarkdownState(openable, retainState = true)
    val handler = remember(onOpenLink, onOpenRich) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val hostTarget = HostFileTarget.fromInternalUri(uri)
                if (hostTarget != null && onOpenRich != null) {
                    onOpenRich(RichModalRequest.HostFile(hostTarget))
                } else {
                    onOpenLink(uri)
                }
            }
        }
    }
    val type = MaterialTheme.typography
    val typography = markdownTypography(
        h1 = type.titleLarge,
        h2 = type.titleMedium.copy(fontWeight = FontWeight.Bold),
        h3 = type.titleSmall.copy(fontWeight = FontWeight.Bold),
        h4 = type.bodyLarge.copy(fontWeight = FontWeight.Bold),
        h5 = type.bodyMedium.copy(fontWeight = FontWeight.Bold),
        h6 = type.bodyMedium.copy(fontWeight = FontWeight.Bold),
        textLink = TextLinkStyles(
            style = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
        ),
    )
    val components = remember(onOpenRich) {
        markdownComponents(
            table = { model -> MarkdownTableBlock(model, onOpenRich) },
            codeFence = { model -> MarkdownCodeOrDiagramBlock(model, onOpenRich) },
            codeBlock = { model -> MarkdownCodeOrDiagramBlock(model, onOpenRich) },
        )
    }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(
            state,
            typography = typography,
            components = components,
            modifier = modifier,
        )
    }
}

@Composable
private fun MarkdownTableBlock(
    model: MarkdownComponentModel,
    onOpenRich: ((RichModalRequest) -> Unit)?,
) {
    val table = remember(model.content, model.node.startOffset, model.node.endOffset) {
        TableData.parseMarkdown(model.content, model.node.startOffset, model.node.endOffset)
    }
    if (table == null) {
        val raw = model.content.substring(
            model.node.startOffset.coerceIn(0, model.content.length),
            model.node.endOffset.coerceIn(0, model.content.length),
        )
        Text(raw, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        return
    }

    val platformActions = rememberPlatformUiActions()
    val hints = remember(table) { table.columnCharHints() }
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .testTag("markdown_table"),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Table (${table.rowCount}\u00d7${table.columnCount})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { platformActions.copyToClipboard("Table Markdown", table.toMarkdown()) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Copy table" }
                        .testTag("table_copy"),
                ) {
                    Text("Copy", style = MaterialTheme.typography.labelMedium)
                }
                if (onOpenRich != null) {
                    TextButton(
                        onClick = { onOpenRich(RichModalRequest.Table("Table", table)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Expand table" }
                            .testTag("table_expand"),
                    ) {
                        Text("Expand", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            HorizontalDivider(color = borderColor)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                DataTableGrid(table = table, columnHints = hints, scale = 1f)
            }
        }
    }
}

@Composable
private fun MarkdownCodeOrDiagramBlock(
    model: MarkdownComponentModel,
    onOpenRich: ((RichModalRequest) -> Unit)?,
) {
    val (lang, code) = remember(model.content, model.node.startOffset, model.node.endOffset) {
        val start = model.node.startOffset.coerceIn(0, model.content.length)
        val end = model.node.endOffset.coerceIn(start, model.content.length)
        extractCodeBlock(model.content.substring(start, end))
    }
    val diagram = remember(lang, code) {
        if (DiagramModel.isDiagramFence(lang, code)) DiagramModel.parse(lang, code) else null
    }
    if (diagram != null) {
        MarkdownDiagramCard(
            language = lang ?: "svg",
            sourceCode = code,
            diagram = diagram,
            onOpenRich = onOpenRich,
        )
    } else {
        MarkdownCodeCard(
            language = lang,
            code = code,
            onOpenRich = onOpenRich,
        )
    }
}

@Composable
private fun MarkdownDiagramCard(
    language: String,
    sourceCode: String,
    diagram: DiagramModel,
    onOpenRich: ((RichModalRequest) -> Unit)?,
) {
    val platformActions = rememberPlatformUiActions()
    var showCode by remember { mutableStateOf(false) }
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .testTag("markdown_diagram"),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = diagram.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { showCode = !showCode },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = if (showCode) "Show diagram preview" else "Show diagram code" }
                        .testTag("diagram_toggle_code"),
                ) {
                    Text(if (showCode) "Preview" else "Code", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = { platformActions.copyToClipboard(language, sourceCode) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Copy diagram source" }
                        .testTag("code_copy"),
                ) {
                    Text("Copy", style = MaterialTheme.typography.labelMedium)
                }
                if (onOpenRich != null) {
                    TextButton(
                        onClick = { onOpenRich(RichModalRequest.Diagram(diagram.title, language, sourceCode, diagram)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Expand diagram" }
                            .testTag("diagram_expand"),
                    ) {
                        Text("Expand", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            HorizontalDivider(color = borderColor)
            if (showCode) {
                Text(
                    text = sourceCode,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                        .testTag("diagram_source_text"),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    DiagramCanvas(diagram = diagram)
                }
            }
        }
    }
}

@Composable
private fun MarkdownCodeCard(
    language: String?,
    code: String,
    onOpenRich: ((RichModalRequest) -> Unit)?,
) {
    val platformActions = rememberPlatformUiActions()
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val label = language?.takeIf { it.isNotBlank() } ?: "code"

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .testTag("markdown_code_block"),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { platformActions.copyToClipboard(label, code) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Copy $label" }
                        .testTag("code_copy"),
                ) {
                    Text("Copy", style = MaterialTheme.typography.labelMedium)
                }
                if (onOpenRich != null) {
                    TextButton(
                        onClick = { onOpenRich(RichModalRequest.Code(label, language, code)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Expand $label" }
                            .testTag("code_expand"),
                    ) {
                        Text("Expand", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            HorizontalDivider(color = borderColor)
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
        }
    }
}

internal fun extractCodeBlock(raw: String): Pair<String?, String> {
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    if (lines.isEmpty()) return null to ""
    val firstTrimmed = lines.first().trimStart()
    val fenceChar = firstTrimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
    if (fenceChar != null) {
        val fenceLen = firstTrimmed.takeWhile { it == fenceChar }.length
        if (fenceLen >= 3) {
            val lang = firstTrimmed.substring(fenceLen).trim().substringBefore(' ').takeIf { it.isNotEmpty() }
            val hasClosing = lines.size >= 2 && lines.last().trim().let { last ->
                last.length >= fenceLen && last.all { it == fenceChar }
            }
            val bodyLines = if (hasClosing) lines.subList(1, lines.size - 1) else lines.drop(1)
            return lang to bodyLines.joinToString("\n")
        }
    }
    val body = lines.joinToString("\n") { line ->
        var spaces = 0
        while (spaces < 4 && spaces < line.length && line[spaces] == ' ') spaces++
        line.substring(spaces)
    }
    return null to body
}

private val CELL_TOKEN = Regex("""`([^`]+)`|\*\*([^*]+)\*\*|\*([^*]+)\*|\[([^\]\n]+)]\(([^()\s]+)\)""")

/**
 * Formats inline markdown inside a table cell (inline code, bold, italic, and tappable links) into an [AnnotatedString].
 */
internal fun formatCellMarkdown(
    raw: String,
    linkColor: Color,
    codeBg: Color,
    onOpenUri: ((String) -> Unit)? = null,
): AnnotatedString {
    if ('`' !in raw && '*' !in raw && '[' !in raw) return AnnotatedString(raw)
    return buildAnnotatedString {
        var lastIndex = 0
        for (match in CELL_TOKEN.findAll(raw)) {
            if (match.range.first > lastIndex) {
                append(raw.substring(lastIndex, match.range.first))
            }
            val codeSpan = match.groups[1]?.value
            val boldSpan = match.groups[2]?.value
            val italicSpan = match.groups[3]?.value
            val linkLabel = match.groups[4]?.value
            val linkUrl = match.groups[5]?.value

            when {
                codeSpan != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                    append(codeSpan)
                }
                boldSpan != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(boldSpan)
                }
                italicSpan != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(italicSpan)
                }
                linkLabel != null && linkUrl != null -> {
                    val linkStyle = TextLinkStyles(
                        style = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
                    )
                    val annotation = LinkAnnotation.Url(
                        url = linkUrl,
                        styles = linkStyle,
                        linkInteractionListener = if (onOpenUri != null) {
                            LinkInteractionListener { onOpenUri(linkUrl) }
                        } else {
                            null
                        },
                    )
                    withLink(annotation) {
                        append(linkLabel)
                    }
                }
                else -> append(match.value)
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < raw.length) {
            append(raw.substring(lastIndex))
        }
    }
}
