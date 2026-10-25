package dev.ahpclient.state

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** How long ago something happened, in the fewest characters a list row has room for: "now", "5m", "2h", "3d", then the date. */
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
        if (seconds < MINUTE) return "now"
        if (seconds < HOUR) return "${seconds / MINUTE}m"
        if (seconds < DAY) return "${seconds / HOUR}h"
        val days = Duration.between(then.atZone(zone).toLocalDate().atStartOfDay(zone), now.atZone(zone).toLocalDate().atStartOfDay(zone)).toDays()
        return when {
            days <= 1L -> "1d"
            seconds < WEEK -> "${days}d"
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(then)
        }
    }
}

/** The words for each state. Colour is only ever a second cue. */
val ActivityState.label: String
    get() = when (this) {
        ActivityState.NEEDS_INPUT -> "Needs you"
        ActivityState.IN_PROGRESS -> "Working"
        ActivityState.ERROR -> "Error"
        ActivityState.IDLE -> "Idle"
    }
