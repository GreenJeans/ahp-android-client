package dev.ahpclient.app

import kotlin.test.Test
import kotlin.test.assertEquals

class MonitorToggleTest {
    private class Rig(var granted: Boolean) {
        val applied = mutableListOf<Boolean>()
        val asked = mutableListOf<() -> Unit>()
        val toggle = MonitorToggle({ granted }, { asked += it }, { applied += it })
    }

    @Test
    fun `with the permission, turning on applies at once and never asks`() {
        val rig = Rig(granted = true)

        rig.toggle.request(true)

        assertEquals(listOf(true), rig.applied)
        assertEquals(0, rig.asked.size)
    }

    @Test
    fun `without the permission, it asks first and applies only after the answer`() {
        val rig = Rig(granted = false)

        rig.toggle.request(true)

        assertEquals(1, rig.asked.size)
        assertEquals(emptyList(), rig.applied, "the service must not start while the dialog is up")

        rig.asked.single()()

        assertEquals(listOf(true), rig.applied)
    }

    @Test
    fun `a refusal still turns monitoring on, because it works without alerts`() {
        val rig = Rig(granted = false)

        rig.toggle.request(true)
        rig.granted = false
        rig.asked.single()()

        assertEquals(listOf(true), rig.applied)
    }

    @Test
    fun `a second tap while the question is showing does not ask again`() {
        val rig = Rig(granted = false)

        rig.toggle.request(true)
        rig.toggle.request(true)
        rig.toggle.request(true)

        assertEquals(1, rig.asked.size)
        rig.asked.single()()
        assertEquals(listOf(true), rig.applied)
    }

    @Test
    fun `an answer is applied once even if delivered twice`() {
        val rig = Rig(granted = false)
        rig.toggle.request(true)

        rig.asked.single()()
        rig.asked.single()()

        assertEquals(listOf(true), rig.applied)
    }

    @Test
    fun `turning off never asks, and cancels a question still showing`() {
        val rig = Rig(granted = false)
        rig.toggle.request(true)

        rig.toggle.request(false)
        rig.asked.single()()

        assertEquals(listOf(false), rig.applied, "the late answer must not switch monitoring back on")
        assertEquals(1, rig.asked.size)
    }

    @Test
    fun `turning off with the permission applies off and asks nothing`() {
        val rig = Rig(granted = true)

        rig.toggle.request(false)

        assertEquals(listOf(false), rig.applied)
        assertEquals(0, rig.asked.size)
    }

    @Test
    fun `after an answer the next turn-on asks again if the permission is still missing`() {
        val rig = Rig(granted = false)
        rig.toggle.request(true)
        rig.asked.single()()
        rig.toggle.request(false)

        rig.toggle.request(true)

        assertEquals(2, rig.asked.size)
    }
}
