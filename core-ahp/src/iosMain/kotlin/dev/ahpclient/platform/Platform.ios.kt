package dev.ahpclient.platform

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong as NativeAtomicLong
import kotlin.concurrent.AtomicReference as NativeAtomicReference
import kotlin.experimental.ExperimentalNativeApi
import kotlin.io.encoding.Base64 as KotlinBase64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.native.ref.WeakReference as NativeWeakReference
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.defaultTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithName

actual open class IOException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
    actual constructor(cause: Throwable?) : super(cause)
}

actual open class SocketTimeoutException : IOException {
    actual constructor() : super()
    actual constructor(msg: String?) : super(msg)
}

actual open class DateTimeException : RuntimeException {
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

actual class Instant(private val epochMillis: Long) : Comparable<Instant> {
    actual fun getEpochSecond(): Long = kotlin.math.floor(epochMillis.toDouble() / 1000.0).toLong()
    actual fun toEpochMilli(): Long = epochMillis
    actual fun plusSeconds(secondsToAdd: Long): Instant = Instant(epochMillis + secondsToAdd * 1000L)
    actual fun plusMillis(millisToAdd: Long): Instant = Instant(epochMillis + millisToAdd)
    actual fun minusSeconds(secondsToSubtract: Long): Instant = Instant(epochMillis - secondsToSubtract * 1000L)
    actual fun isBefore(otherInstant: Instant): Boolean = epochMillis < otherInstant.epochMillis
    actual fun isAfter(otherInstant: Instant): Boolean = epochMillis > otherInstant.epochMillis
    actual override fun compareTo(other: Instant): Int = epochMillis.compareTo(other.epochMillis)

    override fun equals(other: Any?): Boolean = other is Instant && epochMillis == other.epochMillis
    override fun hashCode(): Int = epochMillis.hashCode()
    override fun toString(): String = formatIsoMillis(epochMillis, alwaysMillis = false)
}

actual class Duration(private val millis: Long) : Comparable<Duration> {
    actual fun getSeconds(): Long = millis / 1000L
    actual fun toMillis(): Long = millis
    actual fun toDays(): Long = millis / 86_400_000L
    actual override fun compareTo(other: Duration): Int = millis.compareTo(other.millis)

    override fun equals(other: Any?): Boolean = other is Duration && millis == other.millis
    override fun hashCode(): Int = millis.hashCode()
    override fun toString(): String = "PT${millis / 1000.0}S"
}

actual sealed class ZoneId(val id: String) {
    internal abstract val nsTimeZone: NSTimeZone
}

private class IosZoneId(id: String, override val nsTimeZone: NSTimeZone) : ZoneId(id)

actual class Locale(internal val nsLocale: NSLocale)

actual val INSTANT_EPOCH: Instant = Instant(0L)

actual fun instantNow(): Instant = Instant((NSDate().timeIntervalSince1970 * 1000.0).toLong())

actual fun parseInstant(iso: String): Instant = Instant(parseIsoToEpochMillis(iso))

actual fun instantOfEpochMilli(epochMilli: Long): Instant = Instant(epochMilli)

actual fun durationBetween(startInclusive: Instant, endExclusive: Instant): Duration =
    Duration(endExclusive.toEpochMilli() - startInclusive.toEpochMilli())

actual fun durationOfDays(days: Long): Duration = Duration(days * 86_400_000L)

actual fun durationOfMinutes(minutes: Long): Duration = Duration(minutes * 60_000L)

actual fun durationOfSeconds(seconds: Long): Duration = Duration(seconds * 1000L)

actual fun durationOfMillis(millis: Long): Duration = Duration(millis)

actual fun systemDefaultZone(): ZoneId {
    val tz = NSTimeZone.defaultTimeZone
    return IosZoneId(tz.name, tz)
}

fun zoneIdOf(name: String): ZoneId {
    val tz = NSTimeZone.timeZoneWithName(name) ?: NSTimeZone.defaultTimeZone
    return IosZoneId(name, tz)
}

actual fun defaultLocale(): Locale = Locale(NSLocale.currentLocale)

actual fun startOfDayDaysBetween(then: Instant, now: Instant, zone: ZoneId): Long =
    calendarDaysBetween(then, now, zone)

actual fun calendarDaysBetween(then: Instant, now: Instant, zone: ZoneId): Long {
    val cal = NSCalendar.currentCalendar.apply { timeZone = zone.nsTimeZone }
    val startThen = cal.startOfDayForDate(NSDate.dateWithTimeIntervalSince1970(then.toEpochMilli() / 1000.0))
    val startNow = cal.startOfDayForDate(NSDate.dateWithTimeIntervalSince1970(now.toEpochMilli() / 1000.0))
    val comps = cal.components(NSCalendarUnitDay, fromDate = startThen, toDate = startNow, options = 0u)
    return comps.day
}

actual fun formatMediumDate(instant: Instant, zone: ZoneId, locale: Locale): String {
    val fmt = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterNoStyle
        timeZone = zone.nsTimeZone
        this.locale = locale.nsLocale
    }
    return fmt.stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilli() / 1000.0))
}

actual fun formatShortTime(instant: Instant, zone: ZoneId, locale: Locale): String {
    val fmt = NSDateFormatter().apply {
        dateStyle = NSDateFormatterNoStyle
        timeStyle = NSDateFormatterShortStyle
        timeZone = zone.nsTimeZone
        this.locale = locale.nsLocale
    }
    return fmt.stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilli() / 1000.0))
}

actual fun formatShortDateTime(instant: Instant, zone: ZoneId, locale: Locale): String {
    val fmt = NSDateFormatter().apply {
        dateStyle = NSDateFormatterShortStyle
        timeStyle = NSDateFormatterShortStyle
        timeZone = zone.nsTimeZone
        this.locale = locale.nsLocale
    }
    return fmt.stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilli() / 1000.0))
}

actual fun formatInteger(value: Long, locale: Locale): String {
    val s = value.toString()
    val neg = s.startsWith("-")
    val digits = if (neg) s.substring(1) else s
    val sb = StringBuilder()
    for (i in digits.indices) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
        sb.append(digits[i])
    }
    return if (neg) "-$sb" else sb.toString()
}

actual fun addMillisecondsToIsoTimestamp(timestamp: String, durationMs: Long): String =
    formatIsoMillis(parseIsoToEpochMillis(timestamp) + durationMs, alwaysMillis = true)

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun randomUuid(): String = NSUUID().UUIDString.lowercase()

actual fun sha256(bytes: ByteArray): ByteArray = PureSha256.digest(bytes)

@OptIn(ExperimentalEncodingApi::class)
actual fun base64UrlEncodeNoPad(bytes: ByteArray): String =
    KotlinBase64.UrlSafe.withPadding(KotlinBase64.PaddingOption.ABSENT).encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
actual fun base64MimeDecode(data: String): ByteArray? = try {
    KotlinBase64.Mime.decode(data)
} catch (_: IllegalArgumentException) {
    null
}

@OptIn(ExperimentalEncodingApi::class)
actual fun base64Encode(bytes: ByteArray): String = KotlinBase64.Default.encode(bytes)

actual fun decodeUtf8Strict(bytes: ByteArray): String? = try {
    bytes.decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    null
}

actual fun urlEncodeUtf8(value: String): String {
    val bytes = value.encodeToByteArray()
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if ((ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '_' || ch == '.' || ch == '*') {
            sb.append(ch)
        } else if (ch == ' ') {
            sb.append('+')
        } else {
            sb.append('%')
            sb.append(HEX[(c ushr 4) and 0xF])
            sb.append(HEX[c and 0xF])
        }
    }
    return sb.toString()
}

actual fun urlDecodeUtf8(value: String): String? {
    val out = ByteArray(value.length)
    var outIdx = 0
    var i = 0
    while (i < value.length) {
        when (val ch = value[i]) {
            '+' -> {
                out[outIdx++] = ' '.code.toByte()
                i++
            }
            '%' -> {
                if (i + 2 >= value.length) return null
                val hi = hexVal(value[i + 1])
                val lo = hexVal(value[i + 2])
                if (hi < 0 || lo < 0) return null
                out[outIdx++] = ((hi shl 4) or lo).toByte()
                i += 3
            }
            else -> {
                val encoded = ch.toString().encodeToByteArray()
                for (b in encoded) out[outIdx++] = b
                i++
            }
        }
    }
    return decodeUtf8Strict(out.copyOf(outIdx))
}

private const val HEX = "0123456789ABCDEF"
private fun hexVal(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'A'..'F' -> c - 'A' + 10
    in 'a'..'f' -> c - 'a' + 10
    else -> -1
}

actual fun parseUri(raw: String): ParsedUri? {
    if (raw.isEmpty() || raw.any { it.code <= 0x20 || it.code >= 0x7F }) return null
    val colonIdxScheme = raw.indexOf(':')
    if (colonIdxScheme <= 0) return null
    val scheme = raw.substring(0, colonIdxScheme)
    if (!scheme[0].isLetter() || !scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
    val hasAuthority = raw.startsWith("://", colonIdxScheme)
    var rest = if (hasAuthority) raw.substring(colonIdxScheme + 3) else raw.substring(colonIdxScheme + 1)

    val rawFragment = rest.indexOf('#').takeIf { it >= 0 }?.let { idx ->
        val frag = rest.substring(idx + 1)
        rest = rest.substring(0, idx)
        if ('#' in frag) return null
        frag
    }
    val rawQuery = rest.indexOf('?').takeIf { it >= 0 }?.let { idx ->
        val q = rest.substring(idx + 1)
        rest = rest.substring(0, idx)
        q
    }
    if (!hasAuthority) {
        val rawPath = if (rest.startsWith("/")) rest else null
        val decodedPath = rawPath?.let { urlDecodeUtf8(it.replace("+", "%2B")) ?: it }
        return ParsedUri(scheme, null, -1, null, rawPath, rawQuery, rawFragment, decodedPath)
    }
    val slashIdx = rest.indexOf('/')
    val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
    val rawPath = if (slashIdx >= 0) rest.substring(slashIdx) else ""
    val decodedPath = urlDecodeUtf8(rawPath.replace("+", "%2B")) ?: rawPath
    if (authority.isEmpty()) return ParsedUri(scheme, null, -1, null, rawPath, rawQuery, rawFragment, decodedPath)

    val atIdx = authority.lastIndexOf('@')
    val userInfo = if (atIdx >= 0) authority.substring(0, atIdx) else null
    val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
    if (hostPort.isEmpty()) return ParsedUri(scheme, null, -1, userInfo, rawPath, rawQuery, rawFragment, decodedPath)

    val host: String?
    val port: Int
    if (hostPort.startsWith("[")) {
        val closeIdx = hostPort.indexOf(']')
        if (closeIdx < 0) return null
        val ipv6 = hostPort.substring(0, closeIdx + 1)
        val after = hostPort.substring(closeIdx + 1)
        port = if (after.isEmpty()) {
            -1
        } else if (after.startsWith(":") && after.length > 1) {
            after.substring(1).toIntOrNull() ?: return null
        } else {
            return null
        }
        host = if (parseIpv6Literal(ipv6.removeSurrounding("[", "]")) != null) ipv6 else null
    } else {
        val colonIdx = hostPort.indexOf(':')
        if (colonIdx >= 0 && hostPort.indexOf(':', colonIdx + 1) >= 0) return null
        val rawHost = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
        port = if (colonIdx >= 0) {
            val portStr = hostPort.substring(colonIdx + 1)
            if (portStr.isEmpty()) -1 else (portStr.toIntOrNull() ?: return null)
        } else {
            -1
        }
        val validHost = rawHost.isNotEmpty() &&
            !rawHost.startsWith("-") &&
            !rawHost.endsWith("-") &&
            rawHost.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        host = if (validHost) rawHost else null
    }
    return ParsedUri(scheme, host, port, userInfo, rawPath, rawQuery, rawFragment, decodedPath)
}

actual fun parseIpv6Literal(bare: String): ByteArray? {
    val clean = bare.removePrefix("[").removeSuffix("]").lowercase()
    if (clean.isEmpty() || !clean.all { it in "0123456789abcdef:." }) return null
    val doubleColonCount = Regex("::").findAll(clean).count()
    if (doubleColonCount > 1) return null
    if (doubleColonCount == 0 && clean.startsWith(":") || (doubleColonCount == 0 && clean.endsWith(":"))) return null

    val halves = if ("::" in clean) clean.split("::", limit = 2) else listOf(clean)
    val leftGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
    val rightGroups = if (halves.size > 1 && halves[1].isNotEmpty()) halves[1].split(':') else emptyList()

    val expandedRight = ArrayList<Int>()
    for ((idx, g) in rightGroups.withIndex()) {
        if ('.' in g) {
            if (idx != rightGroups.lastIndex) return null
            val v4 = g.split('.')
            if (v4.size != 4) return null
            val b = v4.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
            expandedRight += (b[0] shl 8) or b[1]
            expandedRight += (b[2] shl 8) or b[3]
        } else {
            if (g.isEmpty() || g.length > 4) return null
            expandedRight += g.toIntOrNull(16) ?: return null
        }
    }
    val expandedLeft = ArrayList<Int>()
    for ((idx, g) in leftGroups.withIndex()) {
        if ('.' in g) {
            if (halves.size != 1 || idx != leftGroups.lastIndex) return null
            val v4 = g.split('.')
            if (v4.size != 4) return null
            val b = v4.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
            expandedLeft += (b[0] shl 8) or b[1]
            expandedLeft += (b[2] shl 8) or b[3]
        } else {
            if (g.isEmpty() || g.length > 4) return null
            expandedLeft += g.toIntOrNull(16) ?: return null
        }
    }

    val totalGroups = expandedLeft.size + expandedRight.size
    val allGroups = if (halves.size == 2) {
        if (totalGroups > 7) return null
        expandedLeft + List(8 - totalGroups) { 0 } + expandedRight
    } else {
        if (totalGroups != 8) return null
        expandedLeft
    }
    val out = ByteArray(16)
    for (i in 0 until 8) {
        out[i * 2] = ((allGroups[i] ushr 8) and 0xFF).toByte()
        out[i * 2 + 1] = (allGroups[i] and 0xFF).toByte()
    }
    return out
}

@OptIn(ExperimentalNativeApi::class)
actual class WeakReference<T : Any> actual constructor(referent: T) {
    private val ref = NativeWeakReference(referent)
    actual fun get(): T? = ref.get()
}

actual class AtomicBoolean actual constructor(initialValue: Boolean) {
    private val atom = AtomicInt(if (initialValue) 1 else 0)
    actual fun get(): Boolean = atom.value != 0
    actual fun set(newValue: Boolean) {
        atom.value = if (newValue) 1 else 0
    }
    actual fun compareAndSet(expect: Boolean, update: Boolean): Boolean =
        atom.compareAndSet(if (expect) 1 else 0, if (update) 1 else 0)
    actual fun getAndSet(newValue: Boolean): Boolean =
        atom.getAndSet(if (newValue) 1 else 0) != 0
}

actual class AtomicLong actual constructor(initialValue: Long) {
    private val atom = NativeAtomicLong(initialValue)
    actual fun get(): Long = atom.value
    actual fun set(newValue: Long) {
        atom.value = newValue
    }
    actual fun getAndIncrement(): Long = atom.getAndAdd(1L)
    actual fun incrementAndGet(): Long = atom.addAndGet(1L)
    actual fun compareAndSet(expect: Long, update: Long): Boolean = atom.compareAndSet(expect, update)
}

actual class AtomicReference<V> actual constructor(initialValue: V) {
    private val atom = NativeAtomicReference(initialValue)
    actual fun get(): V = atom.value
    actual fun set(newValue: V) {
        atom.value = newValue
    }
    actual fun compareAndSet(expect: V, update: V): Boolean = atom.compareAndSet(expect, update)
    actual fun getAndSet(newValue: V): V = atom.getAndSet(newValue)
}

@PublishedApi
internal val globalLock = NSRecursiveLock()

actual inline fun <R> platformSynchronized(lock: Any, block: () -> R): R {
    globalLock.lock()
    try {
        return block()
    } finally {
        globalLock.unlock()
    }
}

private val ISO_REGEX = Regex(
    """^([+-]?\d{4,6})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?([Zz]|[+-]\d{2}:\d{2})$""",
)

private fun parseIsoToEpochMillis(iso: String): Long {
    val m = ISO_REGEX.matchEntire(iso.trim()) ?: throw DateTimeException("Invalid ISO-8601 instant: $iso")
    val year = m.groupValues[1].toLongOrNull() ?: throw DateTimeException("Invalid year in $iso")
    val month = m.groupValues[2].toInt()
    val day = m.groupValues[3].toInt()
    val hour = m.groupValues[4].toInt()
    val minute = m.groupValues[5].toInt()
    val second = m.groupValues[6].toInt()
    if (month !in 1..12 || day !in 1..maxDayOfMonth(year, month) || hour !in 0..23 || minute !in 0..59 || second !in 0..60) {
        throw DateTimeException("Out-of-range field in $iso")
    }
    val fracStr = m.groupValues[7]
    val millis = if (fracStr.isEmpty()) 0L else fracStr.padEnd(3, '0').substring(0, 3).toLong()
    val tzStr = m.groupValues[8]
    val offsetSeconds = if (tzStr.equals("Z", ignoreCase = true)) {
        0L
    } else {
        val sign = if (tzStr[0] == '-') -1L else 1L
        val tzH = tzStr.substring(1, 3).toLong()
        val tzM = tzStr.substring(4, 6).toLong()
        if (tzH > 18 || tzM > 59) throw DateTimeException("Invalid timezone offset in $iso")
        sign * (tzH * 3600L + tzM * 60L)
    }
    val epochDays = civilToEpochDays(year, month, day)
    val totalSeconds = epochDays * 86_400L + hour * 3600L + minute * 60L + second - offsetSeconds
    return totalSeconds * 1000L + millis
}

private fun formatIsoMillis(epochMillis: Long, alwaysMillis: Boolean): String {
    val epochSec = kotlin.math.floor(epochMillis.toDouble() / 1000.0).toLong()
    val millis = ((epochMillis % 1000L) + 1000L) % 1000L
    val epochDays = kotlin.math.floor(epochSec.toDouble() / 86_400.0).toLong()
    val secOfDay = ((epochSec % 86_400L) + 86_400L) % 86_400L
    val (y, m, d) = epochDaysToCivil(epochDays)
    val hh = (secOfDay / 3600L).toInt()
    val mm = ((secOfDay % 3600L) / 60L).toInt()
    val ss = (secOfDay % 60L).toInt()
    val yStr = if (y in 0..9999) y.toString().padStart(4, '0') else y.toString()
    val base = "$yStr-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}T" +
        "${hh.toString().padStart(2, '0')}:${mm.toString().padStart(2, '0')}:${ss.toString().padStart(2, '0')}"
    return if (alwaysMillis || millis != 0L) {
        "$base.${millis.toString().padStart(3, '0')}Z"
    } else {
        "${base}Z"
    }
}

private fun isLeapYear(y: Long): Boolean = (y % 4L == 0L) && (y % 100L != 0L || y % 400L == 0L)

private fun maxDayOfMonth(y: Long, m: Int): Int = when (m) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(y)) 29 else 28
    else -> 0
}

private fun civilToEpochDays(yInput: Long, m: Int, d: Int): Long {
    val y = if (m <= 2) yInput - 1 else yInput
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yoe = y - era * 400
    val mp = if (m > 2) m - 3 else m + 9
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

private fun epochDaysToCivil(zInput: Long): Triple<Long, Int, Int> {
    val z = zInput + 719468
    val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = if (m <= 2) y + 1 else y
    return Triple(year, m, d)
}

private object PureSha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    fun digest(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8L
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            msg[msg.size - 1 - i] = ((bitLen ushr (i * 8)) and 0xFF).toByte()
        }

        var h0 = 0x6a09e667
        var h1 = -0x4498517b
        var h2 = 0x3c6ef372
        var h3 = -0x5ab00ac6
        var h4 = 0x510e527f
        var h5 = -0x64fa9774
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val w = IntArray(64)
        var offset = 0
        while (offset < msg.size) {
            for (i in 0 until 16) {
                val j = offset + i * 4
                w[i] = ((msg[j].toInt() and 0xFF) shl 24) or
                    ((msg[j + 1].toInt() and 0xFF) shl 16) or
                    ((msg[j + 2].toInt() and 0xFF) shl 8) or
                    (msg[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7

            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
            offset += 64
        }

        val out = ByteArray(32)
        val hs = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
        for (i in hs.indices) {
            out[i * 4] = (hs[i] ushr 24).toByte()
            out[i * 4 + 1] = (hs[i] ushr 16).toByte()
            out[i * 4 + 2] = (hs[i] ushr 8).toByte()
            out[i * 4 + 3] = hs[i].toByte()
        }
        return out
    }
}
