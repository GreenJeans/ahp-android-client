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

    /** Rounds to the nearest step: what the slider in Settings offers. */
    fun snap(scale: Float): Float = clamp((clamp(scale) / STEP).roundToInt() * STEP)

    /** Rounds to a whole percent, so a pinch that follows the fingers is smooth but what is kept is tidy. */
    fun round(scale: Float): Float = clamp(kotlin.math.round(clamp(scale) * 100f) / 100f)

    /** "110%". */
    fun percent(scale: Float): String = "${(clamp(scale) * 100).roundToInt()}%"
}

/**
 * The kinds of view that show text as written, line for line, and so can either wrap a long line or keep it whole and scroll
 * sideways. The person's choice is kept per kind, so wrapping one code block wraps them all. [key] is what the choice is saved
 * under, so renaming an entry does not lose it. Code, tool output and files keep lines whole unless asked, because their layout
 * is often the point; the terminal and diffs wrap, as they always have. A markdown table "wraps" when its columns are fitted to
 * the width of the screen, so its cells wrap instead of the table scrolling sideways; it keeps its column widths unless asked.
 */
enum class WrapView(val key: String, val wrapsByDefault: Boolean) {
    CODE("code", false),
    TOOL_OUTPUT("tool_output", false),
    FILE("file", false),
    TERMINAL("terminal", true),
    DIFF("diff", true),
    TABLE("table", false),
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