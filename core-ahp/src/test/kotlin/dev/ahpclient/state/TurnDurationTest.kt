package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnDurationTest {
    @Test
    fun `a duration is written in the fewest words`() {
        assertEquals("0s", TurnDuration.format(0))
        assertEquals("4s", TurnDuration.format(4_000))
        assertEquals("59s", TurnDuration.format(59_400))
        assertEquals("1m 0s", TurnDuration.format(59_600))
        assertEquals("1m 4s", TurnDuration.format(64_000))
        assertEquals("59m 59s", TurnDuration.format(3_599_000))
        assertEquals("2h 5m", TurnDuration.format(7_500_000))
    }

    @Test
    fun `an unknown or negative duration is not written`() {
        assertNull(TurnDuration.format(null))
        assertNull(TurnDuration.format(-1))
    }
}