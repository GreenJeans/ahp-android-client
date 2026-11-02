package dev.ahpclient.state

import dev.ahpclient.platform.DateTimeException
import dev.ahpclient.platform.Instant
import dev.ahpclient.platform.Locale
import dev.ahpclient.platform.ZoneId
import dev.ahpclient.platform.defaultLocale
import dev.ahpclient.platform.durationBetween
import dev.ahpclient.platform.formatMediumDate
import dev.ahpclient.platform.parseInstant
import dev.ahpclient.platform.seconds
import dev.ahpclient.platform.startOfDayDaysBetween
import dev.ahpclient.platform.systemDefaultZone

/** How long ago something happened, in the fewest characters a list row has room for: "now", "5m", "2h", "3d", then the date. */
object RelativeTime {
    private const val MINUTE = 60L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY

    /** An unreadable timestamp gives an empty string, so a row never shows garbage. */
    fun format(iso: String, now: Instant, zone: ZoneId = systemDefaultZone(), locale: Locale = defaultLocale()): String {
        val then = try {
            parseInstant(iso)
        } catch (_: DateTimeException) {
            return ""
        }
        val seconds = durationBetween(then, now).seconds
        // A phone clock a little behind the host's would otherwise show "in the future".
        if (seconds < MINUTE) return "now"
        if (seconds < HOUR) return "${seconds / MINUTE}m"
        if (seconds < DAY) return "${seconds / HOUR}h"
        val days = startOfDayDaysBetween(then, now, zone)
        return when {
            days <= 1L -> "1d"
            seconds < WEEK -> "${days}d"
            else -> formatMediumDate(then, zone, locale)
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
