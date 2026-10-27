package dev.ahpclient.app

/**
 * When the periodic check should tell the person that monitoring has stopped. It runs in whatever process the system started for it,
 * where nothing has called `start()` yet, so a state of [AppState.Restoring] means this process has no live session, the same as a
 * host list or a sign-in screen does. Only a live host connection counts as monitoring. With the app in front the person can see
 * what state it is in and a notification would only sit over it, so nothing is posted then.
 */
object WatchdogDecision {
    fun shouldWarn(monitoringEnabled: Boolean, state: AppState, appInFront: Boolean): Boolean =
        monitoringEnabled && !appInFront && state !is AppState.Connected

    /** What the warning says. Tapping it opens the app, which connects again when monitoring is on. */
    const val STOPPED_TEXT = "Monitoring stopped. Tap to resume."
}
