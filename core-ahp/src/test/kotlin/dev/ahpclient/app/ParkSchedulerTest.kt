package dev.ahpclient.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [ParkScheduler] in virtual time: a grace period before parking, nothing for a quick return, one wait however often it is told. */
@OptIn(ExperimentalCoroutinesApi::class)
class ParkSchedulerTest {
    private class Rig(val scheduler: ParkScheduler, val calls: MutableList<String>)

    private fun TestScope.rig(graceMs: Long = 30_000): Rig {
        val calls = mutableListOf<String>()
        return Rig(ParkScheduler(backgroundScope, graceMs, park = { calls += "park" }, unpark = { calls += "unpark" }), calls)
    }

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `leaving parks after the grace period and not before`() = runTest {
        val rig = rig()

        rig.scheduler.visible(false)
        pass(29_999)
        assertEquals(emptyList(), rig.calls)
        assertFalse(rig.scheduler.parked)

        pass(1)
        assertEquals(listOf("park"), rig.calls)
        assertTrue(rig.scheduler.parked)
    }

    @Test
    fun `the grace period is the one given`() = runTest {
        val rig = rig(graceMs = 5_000)

        rig.scheduler.visible(false)
        pass(4_999)
        assertEquals(emptyList(), rig.calls)
        pass(1)

        assertEquals(listOf("park"), rig.calls)
    }

    @Test
    fun `a quick return sends nothing, and later waiting does not park`() = runTest {
        val rig = rig()

        rig.scheduler.visible(false)
        pass(10_000)
        rig.scheduler.visible(true)
        pass(300_000)

        assertEquals(emptyList(), rig.calls)
        assertFalse(rig.scheduler.parked)
    }

    @Test
    fun `being told it is in sight when nothing was pending does nothing`() = runTest {
        val rig = rig()

        rig.scheduler.visible(true)
        rig.scheduler.visible(true)
        pass(300_000)

        assertEquals(emptyList(), rig.calls)
    }

    @Test
    fun `repeated stops do not stack, each one starts the wait again, and it parks once`() = runTest {
        val rig = rig()

        rig.scheduler.visible(false)
        pass(10_000)
        rig.scheduler.visible(false)
        pass(10_000)
        rig.scheduler.visible(false)
        pass(29_999)
        assertEquals(emptyList(), rig.calls, "30 s after the last stop, not the first")

        pass(1)
        assertEquals(listOf("park"), rig.calls)

        pass(300_000)
        assertEquals(listOf("park"), rig.calls)
    }

    @Test
    fun `a stop after it has parked does not park again`() = runTest {
        val rig = rig()
        rig.scheduler.visible(false)
        pass(30_000)

        rig.scheduler.visible(false)
        pass(300_000)

        assertEquals(listOf("park"), rig.calls)
        assertTrue(rig.scheduler.parked)
    }

    @Test
    fun `a return after it has parked unparks once`() = runTest {
        val rig = rig()
        rig.scheduler.visible(false)
        pass(30_000)

        rig.scheduler.visible(true)
        rig.scheduler.visible(true)
        pass(300_000)

        assertEquals(listOf("park", "unpark"), rig.calls)
        assertFalse(rig.scheduler.parked)
    }

    @Test
    fun `leaving again after a return waits out a new grace period`() = runTest {
        val rig = rig()
        rig.scheduler.visible(false)
        pass(30_000)
        rig.scheduler.visible(true)

        rig.scheduler.visible(false)
        pass(29_999)
        assertEquals(listOf("park", "unpark"), rig.calls)

        pass(1)
        assertEquals(listOf("park", "unpark", "park"), rig.calls)
    }

    @Test
    fun `ifParked runs its action only while parked`() = runTest {
        val rig = rig()
        val ran = mutableListOf<String>()

        rig.scheduler.ifParked { ran += "before" }
        rig.scheduler.visible(false)
        pass(30_000)
        rig.scheduler.ifParked { ran += "parked" }
        rig.scheduler.visible(true)
        rig.scheduler.ifParked { ran += "after" }

        assertEquals(listOf("parked"), ran)
    }
}
