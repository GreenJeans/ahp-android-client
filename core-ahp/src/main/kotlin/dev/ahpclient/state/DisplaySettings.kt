package dev.ahpclient.state

import kotlin.math.roundToInt

/** The size of the text in a chat, as a multiple of the system's: set in Settings or by pinching the chat. Pure, so it is tested without a UI. */
object TextScale {
    const val MIN = 0.8f
    const val MAX = 1.6f
    const val DEFAULT = 1f
    const val STEP = 0.05f

    /** Keeps [scale] inside the range the screens are laid out for; a value that is not a number is the default. */
    fun clamp(scale: Float): Float = if (scale.isNaN()) DEFAULT else scale.coerceIn(MIN, MAX)

    /** Rounds to the nearest step, so a pinch changes the text in even steps and does not redraw the chat for every pixel of it. */
    fun snap(scale: Float): Float = clamp((clamp(scale) / STEP).roundToInt() * STEP)

    /** "110%". */
    fun percent(scale: Float): String = "${(clamp(scale) * 100).roundToInt()}%"
}

/** Which colours the app uses: those of the system's light or dark setting, or one of them always. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    companion object {
        /** The mode saved under [name], or the system's when it is missing or from some other version. */
        fun of(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}