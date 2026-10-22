package dev.ahpclient.state

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** How long ago something happened, in the few words a list row has room for. */
object RelativeTime {
    private const val MINUTE = 60L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY

    /** An unreadable timestamp gives an empty string, so a row never shows garbage. */
    fun format(iso: String, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val then = try {
            Instant.parse(iso)
        } catch (_: DateTimeException) {
            return ""
        }
        val seconds = Duration.between(then, now).seconds
        // A phone clock a little behind the host's would otherwise show "in the future".
        if (seconds < MINUTE) return "just now"
        if (seconds < HOUR) return "${seconds / MINUTE} min ago"
        if (seconds < DAY) return "${seconds / HOUR} h ago"
        val days = Duration.between(then.atZone(zone).toLocalDate().atStartOfDay(zone), now.atZone(zone).toLocalDate().atStartOfDay(zone)).toDays()
        return when {
            days <= 1L -> "yesterday"
            seconds < WEEK -> "$days days ago"
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(then)
        }
    }
}

/** The words for each state. Colour is only ever a second cue. */
val ActivityState.label: String
    get() = when (this) {
        ActivityState.NEEDS_INPUT -> "Needs input"
        ActivityState.IN_PROGRESS -> "In progress"
        ActivityState.ERROR -> "Error"
        ActivityState.IDLE -> "Idle"
    }
