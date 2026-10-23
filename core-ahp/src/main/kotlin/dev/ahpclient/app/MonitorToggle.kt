package dev.ahpclient.app

/**
 * The "Monitor in background" switch. Turning it on starts a foreground service that posts a persistent notification at
 * once. Android drops a foreground-service notification posted while the notification permission is missing, and it does
 * not appear until the app posts it again, which here can be a long wait (observed: about 23 minutes). So the
 * permission is asked for first, and monitoring starts only after the answer. A "no" still turns monitoring on:
 * keeping the connection alive works without alerts, and the screen says that alerts are off.
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
