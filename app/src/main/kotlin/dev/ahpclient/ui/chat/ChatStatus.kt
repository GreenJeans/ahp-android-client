package dev.ahpclient.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.ui.AppIcons

/**
 * The end of a chat while a turn is running: three pulsing dots and what the agent is doing, for as long as it works, so a quiet
 * moment is never mistaken for a stop. When the turn is held up on the person it does not pulse and says so, because then nothing
 * is being worked on. Read out politely when it changes.
 */
@Composable
internal fun WorkingRow(activity: String?, waiting: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = if (waiting) "Waiting for you" else activity?.takeIf { it.isNotBlank() } ?: "Working\u2026"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("row_working"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (waiting) Icon(AppIcons.Lock, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp)) else PulseDots()
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (waiting) colors.tertiary else colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("working_text"),
        )
    }
}

/** Three dots that take turns to fade in and out. Decorative: the words next to them say the same. */
@Composable
private fun PulseDots() {
    val transition = rememberInfiniteTransition(label = "working")
    val color = MaterialTheme.colorScheme.primary
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("working_dots").clearAndSetSemantics {},
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 600, easing = LinearEasing), RepeatMode.Reverse, StartOffset(index * 200)),
                label = "dot$index",
            )
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

/** A thin bar across the top of the chat that moves for as long as the agent works. Decorative: the row at the end of the chat says it in words. */
@Composable
internal fun WorkingBar(modifier: Modifier = Modifier) {
    LinearProgressIndicator(modifier = modifier.fillMaxWidth().height(2.dp).testTag("chat_progress").clearAndSetSemantics {})
}