package dev.ahpclient.state

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackoffTest {
    private val plain = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)

    /** A [Random] whose every double is [value], to put the jitter at one end of its range. */
    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = value
    }

    @Test
    fun `every delay stays within a fifth either side of the plain schedule and never above the cap`() {
        val backoff = Backoff(random = Random(42))

        repeat(500) {
            plain.forEachIndexed { index, base ->
                val delay = backoff.delayMs(index + 1)
                val floor = base * 8 / 10
                val ceiling = minOf(base * 12 / 10, 30_000L)
                assertTrue(delay in floor..ceiling, "attempt ${index + 1}: $delay ms is outside $floor..$ceiling")
            }
        }
    }

    @Test
    fun `the extremes of the jitter are a fifth under and a fifth over, the latter held at the cap`() {
        val lowest = Backoff(random = FixedRandom(0.0))
        val highest = Backoff(random = FixedRandom(Math.nextDown(1.0)))

        assertEquals(listOf(800L, 1_600L, 3_200L, 6_400L, 12_800L, 24_000L, 24_000L), (1..7).map(lowest::delayMs))
        assertEquals(listOf(1_200L, 2_400L, 4_800L, 9_600L, 19_200L, 30_000L, 30_000L), (1..7).map(highest::delayMs))
    }

    @Test
    fun `the delays differ from one attempt to the next, and repeat for the same seed`() {
        val first = Backoff(random = Random(7))
        val spread = (1..50).map { first.delayMs(3) }.toSet()
        val again = Backoff(random = Random(7))

        assertTrue(spread.size > 1, "a jittered delay is not the same every time")
        assertEquals((1..50).map { again.delayMs(3) }.toSet(), spread)
    }

    @Test
    fun `no jitter gives the plain schedule whatever the random says`() {
        val backoff = Backoff(jitter = 0.0, random = FixedRandom(0.999))

        assertEquals(plain, (1..7).map(backoff::delayMs))
    }
}
