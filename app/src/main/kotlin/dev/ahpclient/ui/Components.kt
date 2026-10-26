package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.label

/** Why the user is looking at this screen, for example an expired sign-in. Announced to TalkBack when it appears. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/** A spinner with a text label: progress is never conveyed by the animation alone. */
@Composable
fun Busy(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        content()
    }
}

/** The words for each connection state. Never includes a token: the causes are typed errors with fixed messages. */
fun describe(connection: ConnState): String = when (connection) {
    ConnState.Stopped -> "Disconnected"
    ConnState.Connecting -> "Connecting…"
    ConnState.Online -> "Connected"
    is ConnState.Reconnecting -> "Connection lost. Reconnecting (attempt ${connection.attempt})…"
    ConnState.WaitingForNetwork -> "Waiting for network…"
    is ConnState.Failed -> connection.cause.message ?: "Could not connect (${connection.cause.javaClass.simpleName})."
}

/** Where the connection stands, as text. A failure is an error; anything short of connected is announced when it changes. */
@Composable
fun ConnectionLine(connection: ConnState, modifier: Modifier = Modifier) {
    if (connection is ConnState.Failed) {
        ErrorText(describe(connection), modifier.testTag("connection_status"))
    } else {
        Text(describe(connection), style = MaterialTheme.typography.bodyLarge, modifier = modifier.testTag("connection_status"))
    }
}

/** The state in words, tinted with a role colour. The label always carries the meaning, so colour is never the only cue. */
@Composable
fun StatusChip(state: ActivityState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        ActivityState.NEEDS_INPUT -> colors.tertiaryContainer to colors.onTertiaryContainer
        ActivityState.IN_PROGRESS -> colors.primaryContainer to colors.onPrimaryContainer
        ActivityState.ERROR -> colors.errorContainer to colors.onErrorContainer
        ActivityState.IDLE -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.semantics(mergeDescendants = true) {}.testTag("status_chip"),
    ) {
        Text(state.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
