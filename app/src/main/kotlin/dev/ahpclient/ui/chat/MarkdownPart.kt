package dev.ahpclient.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Agent output as formatted text. The text is untrusted, so this renders less than the library could: no image loader is
 * installed, so an image link in the text is never fetched, and every link tap goes to [onOpenLink] instead of straight to a
 * browser, so the caller can ask the user first. Parsing happens off the composition, and the last result stays on screen
 * while a streaming part is re-parsed, so growing text does not flicker.
 *
 * Headings use the title styles: the library's defaults map `#` to the display sizes, which fill a phone screen.
 */
@Composable
fun MarkdownPart(text: String, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberMarkdownState(text, retainState = true)
    val handler = remember(onOpenLink) {
        object : UriHandler {
            override fun openUri(uri: String) = onOpenLink(uri)
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
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(state, typography = typography, modifier = modifier)
    }
}
