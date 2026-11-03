package dev.ahpclient.ui

import androidx.compose.runtime.Composable

/** Which screens are open over a session. More than one can be: a terminal opened from a subagent's transcript, say. */
data class OverlayScreens(
    val subagent: Boolean = false,
    val changeset: Boolean = false,
    val diff: Boolean = false,
    val terminal: Boolean = false,
    val canvas: Boolean = false,
) {
    val any: Boolean get() = subagent || changeset || diff || terminal || canvas
}

/**
 * System back for the screens over a session: each closes the one screen it is on and leaves the rest. A subagent's transcript is
 * left only when nothing is over it, and a changeset only when its file diff is not open. Back from the session itself, from
 * Settings and the inbox, and from the menu is in HomeDrawer.
 */
@Composable
fun OverlayBackHandlers(
    screens: OverlayScreens,
    onCloseChat: () -> Unit,
    onCloseCanvas: () -> Unit,
    onCloseTerminal: () -> Unit,
    onCloseChangeset: () -> Unit,
    onCloseDiff: () -> Unit,
) {
    PlatformBackHandler(enabled = screens.subagent && !screens.terminal && !screens.changeset && !screens.canvas) { onCloseChat() }
    PlatformBackHandler(enabled = screens.canvas) { onCloseCanvas() }
    PlatformBackHandler(enabled = screens.terminal) { onCloseTerminal() }
    PlatformBackHandler(enabled = screens.changeset && !screens.diff) { onCloseChangeset() }
    PlatformBackHandler(enabled = screens.diff) { onCloseDiff() }
}
