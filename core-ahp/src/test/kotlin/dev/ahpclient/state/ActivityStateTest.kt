package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityStateTest {
    private fun status(raw: UInt) = SessionStatus(raw)

    @Test
    fun `input needed wins over in progress because 24 contains 8`() {
        assertEquals(ActivityState.NEEDS_INPUT, status(24u).activityState)
        assertEquals(ActivityState.IN_PROGRESS, status(8u).activityState)
    }

    @Test
    fun `read and archived flags combine with the activity`() {
        val readAndWorking = status(8u or 32u)

        assertEquals(ActivityState.IN_PROGRESS, readAndWorking.activityState)
        assertTrue(readAndWorking.isRead)
        assertFalse(readAndWorking.isArchived)
        assertTrue(status(1u or 64u).isArchived)
    }

    @Test
    fun `error and idle`() {
        assertEquals(ActivityState.ERROR, status(2u).activityState)
        assertEquals(ActivityState.IDLE, status(1u).activityState)
        assertEquals(ActivityState.IDLE, status(1u or 32u).activityState)
    }

    @Test
    fun `an error with input needed still reads as needing input`() {
        assertEquals(ActivityState.NEEDS_INPUT, status(2u or 24u).activityState)
    }
}
