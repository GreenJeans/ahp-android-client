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
 * Pinching with two fingers changes the text size as the fingers move, the way a map zooms: the size at the start of the pinch
 * times how far the fingers have spread, kept within [TextScale]'s range. [onChange] is told each new size while the fingers are
 * down, and [onEnd] the final one when they lift. One finger is left alone, so scrolling and selecting text work as they do without
 * it; only a gesture of two or more is taken.
 *
 * This modifier must sit outside whatever changes the density the text is drawn with: a change of density restarts a pointer
 * handler, which would end the pinch after its first step.
 */
@Composable
internal fun Modifier.pinchToScale(scale: Float, onChange: (Float) -> Unit, onEnd: (Float) -> Unit): Modifier {
    val current by rememberUpdatedState(scale)
    val changed by rememberUpdatedState(onChange)
    val ended by rememberUpdatedState(onEnd)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = current
            var factor = 1f
            var last: Float? = null
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    factor *= event.calculateZoom()
                    val next = TextScale.round(start * factor)
                    if (next != last) {
                        last = next
                        changed(next)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            last?.let { ended(it) }
        }
    }
}
