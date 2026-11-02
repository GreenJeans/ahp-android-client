package dev.ahpclient.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahpclient.platform.Instant
import dev.ahpclient.platform.instantNow
import kotlinx.coroutines.delay

/**
 * The current time, refreshed every [periodMs] while the screen is visible, so "5 min ago" keeps counting between host
 * updates. The ticker pauses when the app is in the background and refreshes at once when it returns.
 */
@Composable
fun rememberNow(periodMs: Long = 30_000, clock: () -> Instant = ::instantNow): State<Instant> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(clock(), lifecycle, periodMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = clock()
            while (true) {
                delay(periodMs)
                value = clock()
            }
        }
    }
}
