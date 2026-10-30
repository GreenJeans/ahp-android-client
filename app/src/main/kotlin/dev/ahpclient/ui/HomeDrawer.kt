package dev.ahpclient.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The side menu is the home page while no session is open and no page (Settings, the inbox) is over it: nothing else would be on screen. */
internal fun isHome(session: String?, pageOpen: Boolean): Boolean = session == null && !pageOpen

/**
 * The side menu's state: open and held open while it is the home page, closed when a session or a page takes its place, and open again
 * when that goes. [session] is the address of the open session, if any; [pageOpen] is true while Settings or the inbox is over the menu.
 * What it was survives rotation.
 */
@Composable
internal fun rememberHomeMenu(session: String?, pageOpen: Boolean): HomeMenu {
    val home = isHome(session, pageOpen)
    val held = rememberUpdatedState(home)
    // A swipe or a tap on the scrim cannot close the menu while it is the home page: there would be a blank page behind it. Material still
    // offers the scrim and a dismiss action to assistive services, and they end here, so the refusal is counted for HomeDrawer to announce.
    val refusals = remember { mutableIntStateOf(0) }
    val drawer = rememberDrawerState(
        initialValue = if (home) DrawerValue.Open else DrawerValue.Closed,
        confirmStateChange = { target ->
            val allowed = target == DrawerValue.Open || !held.value
            if (!allowed) refusals.intValue++
            allowed
        },
    )
    val scope = rememberCoroutineScope()
    // Acts only when what is under the menu changes, so a rotation, which restores the menu as it was, leaves it as it is. A new session
    // while one is open (a notification tap) closes it too. What was acted on is recorded once the menu has settled: the drawer saves only
    // a settled value, so a recreation in the middle of the animation would otherwise restore a closed menu that nothing opens again.
    var lastHome by rememberSaveable { mutableStateOf(home) }
    var lastSession by rememberSaveable { mutableStateOf(session) }
    LaunchedEffect(home, session) {
        if (home != lastHome || session != lastSession) {
            if (home) drawer.open() else drawer.close()
            lastHome = home
            lastSession = session
        }
    }
    return remember(drawer, scope) { HomeMenu(drawer, scope, held, refusals) }
}

@Stable
internal class HomeMenu(
    val drawer: DrawerState,
    private val scope: CoroutineScope,
    private val held: State<Boolean>,
    /** How many times something tried to close the menu while it was held open. */
    val refusals: IntState,
) {
    fun open() {
        scope.launch { drawer.open() }
    }

    /**
     * Closes the menu, unless it is the home page: that one is closed by whatever takes its place (a session, a page), once that is
     * on screen, so it is never closed to a blank page.
     */
    fun close() {
        if (!held.value) scope.launch { drawer.close() }
    }
}

/**
 * The side menu over the main page, and what Back does around it. With no session and no page the menu is the home page and Back puts
 * the app in the background ([onLeaveApp]); over a session it closes the menu, and from the session it opens the menu, leaving the
 * session open behind it; otherwise it leaves a page ([onClosePage]). A screen over the session ([overlayOpen]: a diff, a terminal, a
 * subagent) has its own Back, in the caller.
 */
@Composable
internal fun HomeDrawer(
    menu: HomeMenu,
    session: String?,
    pageOpen: Boolean,
    overlayOpen: Boolean,
    onClosePage: () -> Unit,
    onLeaveApp: () -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = session != null && !overlayOpen) { menu.open() }
    BackHandler(enabled = pageOpen, onBack = onClosePage)
    BackHandler(enabled = !isHome(session, pageOpen) && menu.drawer.isOpen) { menu.close() }
    // Said when a close of the held-open menu is refused (see rememberHomeMenu): the dismiss affordances do nothing, and not silently.
    val view = LocalView.current
    val refusals = menu.refusals.intValue
    LaunchedEffect(refusals) {
        @Suppress("DEPRECATION")
        if (refusals > 0) view.announceForAccessibility("The menu stays open until a session is open.")
    }
    ModalNavigationDrawer(
        drawerState = menu.drawer,
        // Stays on while it is the home page: the scrim only reports a tap to the drawer state, and so to the announcement, with it on.
        gesturesEnabled = !pageOpen && !overlayOpen,
        drawerContent = drawerContent,
    ) {
        // Inside the drawer, so that it is registered after the drawer's own Back handler and takes the press first while the menu is held open.
        BackHandler(enabled = isHome(session, pageOpen), onBack = onLeaveApp)
        content()
    }
}

/**
 * Closes the menu when a notification asks for a session ([requests] counts the taps, 0 for none), also when that session is the one
 * already open: the session does not change then, so nothing else would close the menu.
 */
@Composable
internal fun CloseMenuOnRequest(requests: Int, menu: HomeMenu) {
    LaunchedEffect(requests) { if (requests != 0) menu.close() }
}

/** Calls [onChange] when a host connects or goes away, not when first composed (so a rotation does not repeat it). */
@Composable
internal fun OnConnectionChange(connected: Boolean, onChange: () -> Unit) {
    var was by rememberSaveable { mutableStateOf(connected) }
    LaunchedEffect(connected) {
        if (connected != was) onChange()
        was = connected
    }
}

/**
 * Puts the task in the background, as the system does for a root activity from Android 12, whatever the version: the activity and the
 * connection stay as they are. Never `finish()`.
 */
internal fun leaveApp(context: Context) {
    generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.moveTaskToBack(true)
}
