package dev.ahpclient.app

/**
 * The "Monitor in background" switch. Turning it on starts a foreground service whose notification Android silently
 * drops when the notification permission is missing at that moment, and does not bring back once the permission is
 * granted. So the permission is asked for first, and monitoring starts only after the answer. A "no" still turns
 * monitoring on: keeping the connection alive works without alerts, and the screen says that alerts are off.
 *
 * [askForPermission] must call its argument once the user has answered, whichever way.
 */
class MonitorToggle(
    private val permissionGranted: () -> Boolean,
    private val askForPermission: (onAnswered: () -> Unit) -> Unit,
    private val apply: (Boolean) -> Unit,
) {
    private var asking = false

    @Synchronized
    fun request(on: Boolean) {
        if (!on) {
            // Switching off while the question is showing cancels it, so a late answer cannot turn monitoring back on.
            asking = false
            apply(false)
            return
        }
        if (permissionGranted()) {
            apply(true)
            return
        }
        if (asking) return
        asking = true
        askForPermission(::answered)
    }

    @Synchronized
    private fun answered() {
        if (!asking) return
        asking = false
        apply(true)
    }
}
