package dev.ahpclient.app

import dev.ahpclient.platform.Synchronized

import kotlin.concurrent.Volatile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides when the app has been out of sight long enough to stop streaming to it. [visible] is told when the app leaves and
 * when it returns; [park] runs once it has been gone for [graceMs] without coming back, and [unpark] runs when it returns after
 * that. The wait covers the short hops that are not leaving for good: the biometric prompt, the browser for a GitHub sign-in, a
 * glance at another app. A return inside it cancels the wait and calls nothing, and repeated calls never stack waits.
 */
class ParkScheduler(
    private val scope: CoroutineScope,
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val park: () -> Unit,
    private val unpark: () -> Unit,
) {
    private var wait: Job? = null

    // Counts calls to [visible], so a wait that woke as it was cancelled can tell it is no longer the latest.
    private var generation = 0L

    /** True from the moment [park] was called until the app is seen again. */
    @Volatile
    var parked = false
        private set

    /** Tells the scheduler whether the app can be seen. Leaving starts the grace period again; returning ends it, and undoes the parking if it had happened. */
    @Synchronized
    fun visible(on: Boolean) {
        val mine = ++generation
        wait?.cancel()
        wait = null
        if (on) {
            if (parked) {
                parked = false
                unpark()
            }
            return
        }
        if (parked) return
        wait = scope.launch {
            delay(graceMs)
            parkIfLatest(mine)
        }
    }

    /** Runs [action] if parked, without a call to [visible] able to slip in between the check and the action. */
    @Synchronized
    fun ifParked(action: () -> Unit) {
        if (parked) action()
    }

    @Synchronized
    private fun parkIfLatest(mine: Long) {
        if (mine != generation) return
        wait = null
        parked = true
        park()
    }

    companion object {
        const val DEFAULT_GRACE_MS = 30_000L
    }
}
