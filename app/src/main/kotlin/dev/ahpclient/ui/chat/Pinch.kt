package dev.ahpclient.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import dev.ahpclient.state.TextScale

/**
 * Pinching with two fingers changes the text size, in even steps from where it was when the fingers came down. One finger is left
 * alone, so scrolling and selecting text work as they do without it; only a gesture of two or more is taken. The size is [TextScale]'s.
 */
@Composable
internal fun Modifier.pinchToScale(scale: Float, onScale: (Float) -> Unit): Modifier {
    val current by rememberUpdatedState(scale)
    val callback by rememberUpdatedState(onScale)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = current
            var factor = 1f
            var last = start
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    factor *= event.calculateZoom()
                    val next = TextScale.snap(start * factor)
                    if (next != last) {
                        last = next
                        callback(next)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}