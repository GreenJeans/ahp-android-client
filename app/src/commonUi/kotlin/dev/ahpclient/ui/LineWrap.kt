package dev.ahpclient.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ahpclient.data.UiSettings
import dev.ahpclient.state.WrapView

/**
 * Where the wrap choices are kept: the app provides its settings once at the root, so a choice made in one code block holds for
 * every code block and is still there after a restart. A screen shown without them (a test, a preview) keeps the choice on that
 * screen only.
 */
val LocalLineWrap = staticCompositionLocalOf<UiSettings?> { null }

/** Whether [view]'s kind of view wraps long lines, and how to change it. */
class LineWrap(val on: Boolean, val set: (Boolean) -> Unit)

@Composable
fun rememberLineWrap(view: WrapView): LineWrap {
    val settings = LocalLineWrap.current
    if (settings == null) {
        var local by rememberSaveable(view) { mutableStateOf(view.wrapsByDefault) }
        return LineWrap(local) { local = it }
    }
    val all by settings.lineWrap.collectAsState()
    return LineWrap(all[view] ?: view.wrapsByDefault) { settings.setLineWrap(view, it) }
}

/**
 * The button that turns wrapping on and off for [view]'s kind of view. Its label says what a tap does, so it reads the same to a
 * screen reader in either state; the icon is tinted while wrapping is on.
 */
@Composable
fun WrapToggle(wrap: LineWrap, view: WrapView, modifier: Modifier = Modifier) {
    IconButton(onClick = { wrap.set(!wrap.on) }, modifier = modifier.size(48.dp).testTag("wrap_toggle_${view.key}")) {
        Icon(
            AppIcons.WrapText,
            contentDescription = if (wrap.on) "Keep lines whole" else "Wrap long lines",
            tint = if (wrap.on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The button on a markdown table's card that fits its columns to the width of the screen, or gives them back their own widths.
 * Kept with the wrap choices ([WrapView.TABLE]), so fitting one table fits every table and holds after a restart. Like
 * [WrapToggle], its label says what a tap does and the icon is tinted while the columns are fitted.
 */
@Composable
fun FitColumnsToggle(fit: LineWrap, modifier: Modifier = Modifier) {
    IconButton(onClick = { fit.set(!fit.on) }, modifier = modifier.size(48.dp).testTag("fit_columns_toggle")) {
        Icon(
            AppIcons.FitColumns,
            contentDescription = if (fit.on) "Keep column widths" else "Fit columns to width",
            tint = if (fit.on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Scrolls sideways with [state] unless [wrap] is on, when the text is laid out to the width it has instead. */
fun Modifier.sidewaysUnless(wrap: Boolean, state: ScrollState): Modifier = if (wrap) this else horizontalScroll(state)

/**
 * How wide the widest of [lines] is on one line in [style]. A long list of lines kept whole scrolls sideways as one block, so it
 * needs one width for every row before the rows that are off screen exist. Only the longest few by length are measured: the
 * text is monospace, so the most characters is nearly always the widest, and measuring thousands of lines would be slow.
 */
@Composable
internal fun widestLine(lines: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val px = remember(lines, style, density) {
        lines.sortedByDescending { it.length }.take(WIDEST_SAMPLE)
            .maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1, density = density).size.width } ?: 0
    }
    return with(density) { px.toDp() }
}

private const val WIDEST_SAMPLE = 20
