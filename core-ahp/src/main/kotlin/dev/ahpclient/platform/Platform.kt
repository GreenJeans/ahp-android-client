package dev.ahpclient.platform

expect open class IOException : Exception {
    constructor()
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
    constructor(cause: Throwable?)
}

expect open class SocketTimeoutException : IOException {
    constructor()
    constructor(msg: String?)
}

expect open class DateTimeException : RuntimeException {
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}

expect class Instant : Comparable<Instant> {
    fun getEpochSecond(): Long
    fun toEpochMilli(): Long
    fun plusSeconds(secondsToAdd: Long): Instant
    fun plusMillis(millisToAdd: Long): Instant
    fun minusSeconds(secondsToSubtract: Long): Instant
    fun isBefore(otherInstant: Instant): Boolean
    fun isAfter(otherInstant: Instant): Boolean
    override fun compareTo(other: Instant): Int
}

val Instant.epochSecond: Long get() = getEpochSecond()

expect class Duration : Comparable<Duration> {
    fun getSeconds(): Long
    fun toMillis(): Long
    fun toDays(): Long
    override fun compareTo(other: Duration): Int
}

val Duration.seconds: Long get() = getSeconds()

expect sealed class ZoneId

expect class Locale

expect val INSTANT_EPOCH: Instant

expect fun instantNow(): Instant

expect fun parseInstant(iso: String): Instant

expect fun instantOfEpochMilli(epochMilli: Long): Instant

expect fun durationBetween(startInclusive: Instant, endExclusive: Instant): Duration

expect fun durationOfDays(days: Long): Duration

expect fun durationOfMinutes(minutes: Long): Duration

expect fun durationOfSeconds(seconds: Long): Duration

expect fun durationOfMillis(millis: Long): Duration

expect fun systemDefaultZone(): ZoneId

expect fun defaultLocale(): Locale

expect fun startOfDayDaysBetween(then: Instant, now: Instant, zone: ZoneId): Long

expect fun calendarDaysBetween(then: Instant, now: Instant, zone: ZoneId): Long

expect fun formatMediumDate(instant: Instant, zone: ZoneId, locale: Locale): String

expect fun formatShortTime(instant: Instant, zone: ZoneId, locale: Locale): String

expect fun formatShortDateTime(instant: Instant, zone: ZoneId = systemDefaultZone(), locale: Locale = defaultLocale()): String

expect fun formatInteger(value: Long, locale: Locale = defaultLocale()): String

expect fun addMillisecondsToIsoTimestamp(timestamp: String, durationMs: Long): String

expect fun currentTimeMillis(): Long

expect fun randomUuid(): String

expect fun sha256(bytes: ByteArray): ByteArray

expect fun base64UrlEncodeNoPad(bytes: ByteArray): String

expect fun base64MimeDecode(data: String): ByteArray?

expect fun base64Encode(bytes: ByteArray): String

expect fun decodeUtf8Strict(bytes: ByteArray): String?

expect fun urlEncodeUtf8(value: String): String

expect fun urlDecodeUtf8(value: String): String?

data class ParsedUri(
    val scheme: String?,
    val host: String?,
    val port: Int,
    val userInfo: String?,
    val rawPath: String?,
    val rawQuery: String?,
    val rawFragment: String?,
    val path: String? = rawPath,
)

expect fun parseUri(raw: String): ParsedUri?

expect fun parseIpv6Literal(bare: String): ByteArray?

expect class WeakReference<T : Any>(referent: T) {
    fun get(): T?
}

expect class AtomicBoolean(initialValue: Boolean) {
    fun get(): Boolean
    fun set(newValue: Boolean)
    fun compareAndSet(expect: Boolean, update: Boolean): Boolean
    fun getAndSet(newValue: Boolean): Boolean
}

expect class AtomicLong(initialValue: Long) {
    fun get(): Long
    fun set(newValue: Long)
    fun getAndIncrement(): Long
    fun incrementAndGet(): Long
    fun compareAndSet(expect: Long, update: Long): Boolean
}

expect class AtomicReference<V>(initialValue: V) {
    fun get(): V
    fun set(newValue: V)
    fun compareAndSet(expect: V, update: V): Boolean
    fun getAndSet(newValue: V): V
}

@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
expect annotation class Synchronized()

expect inline fun <R> platformSynchronized(lock: Any, block: () -> R): R

/**
 * Thread-safe map and key-set used across protocol state stores and notifiers.
 */
class ConcurrentHashMap<K, V> : MutableMap<K, V> {
    private val lock = Any()
    private val map = LinkedHashMap<K, V>()

    override val size: Int get() = platformSynchronized(lock) { map.size }
    override fun isEmpty(): Boolean = platformSynchronized(lock) { map.isEmpty() }
    override fun containsKey(key: K): Boolean = platformSynchronized(lock) { map.containsKey(key) }
    override fun containsValue(value: V): Boolean = platformSynchronized(lock) { map.containsValue(value) }
    override fun get(key: K): V? = platformSynchronized(lock) { map[key] }
    override fun put(key: K, value: V): V? = platformSynchronized(lock) { map.put(key, value) }
    override fun remove(key: K): V? = platformSynchronized(lock) { map.remove(key) }
    override fun putAll(from: Map<out K, V>) = platformSynchronized(lock) { map.putAll(from) }
    override fun clear() = platformSynchronized(lock) { map.clear() }

    override val keys: MutableSet<K>
        get() = SynchronizedMutableSet(lock, map.keys)

    override val values: MutableCollection<V>
        get() = platformSynchronized(lock) { ArrayList(map.values) }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = platformSynchronized(lock) { LinkedHashSet(map.entries) }

    companion object {
        fun <E> newKeySet(): MutableSet<E> {
            val lock = Any()
            return SynchronizedMutableSet(lock, LinkedHashSet())
        }
    }
}

private class SynchronizedMutableSet<E>(
    private val lock: Any,
    private val delegate: MutableSet<E>,
) : MutableSet<E> {
    override val size: Int get() = platformSynchronized(lock) { delegate.size }
    override fun isEmpty(): Boolean = platformSynchronized(lock) { delegate.isEmpty() }
    override fun contains(element: E): Boolean = platformSynchronized(lock) { delegate.contains(element) }
    override fun containsAll(elements: Collection<E>): Boolean = platformSynchronized(lock) { delegate.containsAll(elements) }
    override fun add(element: E): Boolean = platformSynchronized(lock) { delegate.add(element) }
    override fun remove(element: E): Boolean = platformSynchronized(lock) { delegate.remove(element) }
    override fun addAll(elements: Collection<E>): Boolean = platformSynchronized(lock) { delegate.addAll(elements) }
    override fun removeAll(elements: Collection<E>): Boolean = platformSynchronized(lock) { delegate.removeAll(elements.toSet()) }
    override fun retainAll(elements: Collection<E>): Boolean = platformSynchronized(lock) { delegate.retainAll(elements.toSet()) }
    override fun clear() = platformSynchronized(lock) { delegate.clear() }
    override fun iterator(): MutableIterator<E> {
        val snapshot = platformSynchronized(lock) { ArrayList(delegate) }
        val it = snapshot.iterator()
        return object : MutableIterator<E> {
            private var last: E? = null
            override fun hasNext(): Boolean = it.hasNext()
            override fun next(): E = it.next().also { last = it }
            override fun remove() {
                @Suppress("UNCHECKED_CAST")
                platformSynchronized(lock) { delegate.remove(last as E) }
            }
        }
    }
}
