package dev.ahpclient.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A summary line that opens and closes a body. The whole header is one button, so a screen reader announces the summary,
 * whether it is open, and that it can be activated, once. Its body is not composed while closed, which keeps a transcript of
 * thousands of blocks cheap. [expanded] lives with the caller so it can outlive the screen.
 */
@Composable
fun CollapsibleBlock(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .testTag("block_header"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            Chevron(expanded)
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp).testTag("block_body"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = body,
            )
        }
    }
}

/** A chevron that points down when closed and up when open. Drawn, because one glyph is not worth a whole icon library. Decorative: the header carries the meaning. */
@Composable
private fun Chevron(expanded: Boolean) {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(24.dp).rotate(if (expanded) 180f else 0f)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val left = Offset(size.width * 0.3f, size.height * 0.4f)
        val middle = Offset(size.width * 0.5f, size.height * 0.62f)
        val right = Offset(size.width * 0.7f, size.height * 0.4f)
        drawLine(color, left, middle, stroke.width, stroke.cap)
        drawLine(color, middle, right, stroke.width, stroke.cap)
    }
}
