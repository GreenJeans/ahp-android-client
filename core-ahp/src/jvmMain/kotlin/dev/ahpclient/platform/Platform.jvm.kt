package dev.ahpclient.platform

import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

actual typealias IOException = java.io.IOException

actual typealias SocketTimeoutException = java.net.SocketTimeoutException

actual typealias DateTimeException = java.time.DateTimeException

actual typealias Instant = java.time.Instant

actual typealias Duration = java.time.Duration

actual typealias ZoneId = java.time.ZoneId

actual typealias Locale = java.util.Locale

actual val INSTANT_EPOCH: Instant = java.time.Instant.EPOCH

actual fun instantNow(): Instant = java.time.Instant.now()

actual fun parseInstant(iso: String): Instant = java.time.Instant.parse(iso)

actual fun instantOfEpochMilli(epochMilli: Long): Instant = java.time.Instant.ofEpochMilli(epochMilli)

actual fun durationBetween(startInclusive: Instant, endExclusive: Instant): Duration =
    java.time.Duration.between(startInclusive, endExclusive)

actual fun durationOfDays(days: Long): Duration = java.time.Duration.ofDays(days)

actual fun durationOfMinutes(minutes: Long): Duration = java.time.Duration.ofMinutes(minutes)

actual fun durationOfSeconds(seconds: Long): Duration = java.time.Duration.ofSeconds(seconds)

actual fun durationOfMillis(millis: Long): Duration = java.time.Duration.ofMillis(millis)

actual fun systemDefaultZone(): ZoneId = java.time.ZoneId.systemDefault()

actual fun defaultLocale(): Locale = java.util.Locale.getDefault()

actual fun startOfDayDaysBetween(then: Instant, now: Instant, zone: ZoneId): Long =
    java.time.Duration.between(
        then.atZone(zone).toLocalDate().atStartOfDay(zone),
        now.atZone(zone).toLocalDate().atStartOfDay(zone),
    ).toDays()

actual fun calendarDaysBetween(then: Instant, now: Instant, zone: ZoneId): Long {
    val sessionDate: LocalDate = then.atZone(zone).toLocalDate()
    val today: LocalDate = now.atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(sessionDate, today)
}

actual fun formatMediumDate(instant: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(instant)

actual fun formatShortTime(instant: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

actual fun formatShortDateTime(instant: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

actual fun formatInteger(value: Long, locale: Locale): String =
    java.text.NumberFormat.getIntegerInstance(locale).format(value)

private val isoTimestampFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

actual fun addMillisecondsToIsoTimestamp(timestamp: String, durationMs: Long): String =
    isoTimestampFormatter.format(java.time.Instant.parse(timestamp).plusMillis(durationMs))

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun base64UrlEncodeNoPad(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

actual fun base64MimeDecode(data: String): ByteArray? = try {
    Base64.getMimeDecoder().decode(data)
} catch (_: IllegalArgumentException) {
    null
}

actual fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

actual fun decodeUtf8Strict(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

actual fun urlEncodeUtf8(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

actual fun urlDecodeUtf8(value: String): String? = try {
    URLDecoder.decode(value, Charsets.UTF_8.name())
} catch (_: IllegalArgumentException) {
    null
}

actual fun parseUri(raw: String): ParsedUri? = try {
    val u = URI(raw)
    ParsedUri(
        scheme = u.scheme,
        host = u.host,
        port = u.port,
        userInfo = u.userInfo,
        rawPath = u.rawPath,
        rawQuery = u.rawQuery,
        rawFragment = u.rawFragment,
        path = u.path,
    )
} catch (_: URISyntaxException) {
    null
}

actual fun parseIpv6Literal(bare: String): ByteArray? = try {
    val bytes = InetAddress.getByName(bare).address
    if (bytes.size == 16) bytes else null
} catch (_: java.net.UnknownHostException) {
    null
}

actual typealias WeakReference<T> = java.lang.ref.WeakReference<T>

actual typealias AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean

actual typealias AtomicLong = java.util.concurrent.atomic.AtomicLong

actual typealias AtomicReference<V> = java.util.concurrent.atomic.AtomicReference<V>

actual typealias Synchronized = kotlin.jvm.Synchronized

actual inline fun <R> platformSynchronized(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)
