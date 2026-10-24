package dev.ahpclient.app

import dev.ahpclient.ahp.CapabilityPolicy
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

/** A saved choice a test can pre-set, break, and read back. */
internal class FakeInteractionSettings(saved: Boolean = false) : InteractionSettings {
    private val value = MutableStateFlow(saved)
    var failReads = false
    var failWrites = false
    val writes = mutableListOf<Boolean>()

    val saved: Boolean get() = value.value

    override val enabled: Flow<Boolean> = flow {
        if (failReads) throw IOException("disk unavailable")
        emit(value.value)
    }

    override suspend fun setEnabled(on: Boolean) {
        if (failWrites) throw IOException("disk full")
        writes += on
        value.value = on
    }
}

class InteractionConsentTest {
    private fun both(interact: Boolean = false, manage: Boolean = false, supported: Boolean = true): Triple<InteractionConsent, FakeInteractionSettings, FakeInteractionSettings> {
        val i = FakeInteractionSettings(interact)
        val m = FakeInteractionSettings(manage)
        return Triple(InteractionConsent(i, supported, m), i, m)
    }

    @Test
    fun `looking after sessions is off by default and the tier stays interact until it is turned on`() = runTest {
        val (consent, _, _) = both(interact = true)
        consent.load()

        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.INTERACT, consent.policy())

        assertTrue(consent.setManagement(true))

        assertTrue(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.MANAGE, consent.policy())
    }

    @Test
    fun `it cannot be turned on while acting for the user is off, and nothing is written`() = runTest {
        val (consent, _, m) = both()
        consent.load()

        assertFalse(consent.setManagement(true))

        assertFalse(consent.managementAllowed.value)
        assertTrue(m.writes.isEmpty())
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `a saved yes to it is ignored while acting for the user is off`() = runTest {
        val (consent, _, _) = both(interact = false, manage = true)

        consent.load()

        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `a saved yes to both gives the manage tier on the next start`() = runTest {
        val (consent, _, _) = both(interact = true, manage = true)

        consent.load()

        assertSame(CapabilityPolicy.MANAGE, consent.policy())
    }

    @Test
    fun `a saved yes to both is ignored on a phone that cannot run the device check`() = runTest {
        val (consent, _, _) = both(interact = true, manage = true, supported = false)

        consent.load()

        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `looking after sessions cannot be turned on without being able to save it`() = runTest {
        val (consent, _, m) = both(interact = true)
        consent.load()
        m.failWrites = true

        assertFalse(consent.setManagement(true))

        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.INTERACT, consent.policy())
    }

    @Test
    fun `a saved looking-after-sessions choice that cannot be read counts as off`() = runTest {
        val (consent, _, m) = both(interact = true, manage = true)
        m.failReads = true

        consent.load()

        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.INTERACT, consent.policy())
    }

    @Test
    fun `turning looking after sessions off takes effect even when it cannot be saved`() = runTest {
        val (consent, _, m) = both(interact = true, manage = true)
        consent.load()
        m.failWrites = true

        assertFalse(consent.setManagement(false))

        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.INTERACT, consent.policy())
    }

    @Test
    fun `turning acting for the user off turns looking after sessions off with it, on screen and on disk`() = runTest {
        val (consent, i, m) = both(interact = true, manage = true)
        consent.load()

        consent.set(false)

        assertFalse(consent.allowed.value)
        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
        assertFalse(i.saved)
        assertFalse(m.saved, "the second permission must not be waiting to come back")
    }

    @Test
    fun `acting for the user turns off even when the second choice cannot be written`() = runTest {
        val (consent, _, m) = both(interact = true, manage = true)
        consent.load()
        m.failWrites = true

        consent.set(false)

        assertFalse(consent.allowed.value)
        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `turning acting for the user back on does not bring looking after sessions back`() = runTest {
        val (consent, _, _) = both(interact = true, manage = true)
        consent.load()
        consent.set(false)

        consent.set(true)

        assertTrue(consent.allowed.value)
        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.INTERACT, consent.policy())
    }

    @Test
    fun `it is off by default and the tier is read only`() {
        val consent = InteractionConsent(FakeInteractionSettings())

        assertFalse(consent.allowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `the saved choice is read once and decides the tier`() = runTest {
        val consent = InteractionConsent(FakeInteractionSettings(saved = true))

        consent.load()

        assertTrue(consent.allowed.value)
        assertSame(CapabilityPolicy.INTERACT, consent.policy())
    }

    @Test
    fun `a saved choice that cannot be read counts as off`() = runTest {
        val settings = FakeInteractionSettings(saved = true).apply { failReads = true }
        val consent = InteractionConsent(settings)

        consent.load()

        assertFalse(consent.allowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `turning it on is saved and then takes effect`() = runTest {
        val settings = FakeInteractionSettings()
        val consent = InteractionConsent(settings)

        assertTrue(consent.set(true))

        assertTrue(consent.allowed.value)
        assertSame(CapabilityPolicy.INTERACT, consent.policy())
        assertEquals(listOf(true), settings.writes)
    }

    @Test
    fun `turning it on without being able to save it grants nothing`() = runTest {
        val settings = FakeInteractionSettings().apply { failWrites = true }
        val consent = InteractionConsent(settings)

        assertFalse(consent.set(true))

        assertFalse(consent.allowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `turning it off takes effect even when it cannot be saved`() = runTest {
        val settings = FakeInteractionSettings(saved = true)
        val consent = InteractionConsent(settings)
        consent.load()
        settings.failWrites = true

        assertFalse(consent.set(false), "the caller is told the choice was not saved")

        assertFalse(consent.allowed.value, "but the permission is gone now")
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `turning it off is saved`() = runTest {
        val settings = FakeInteractionSettings(saved = true)
        val consent = InteractionConsent(settings)
        consent.load()

        assertTrue(consent.set(false))

        assertFalse(consent.allowed.value)
        assertFalse(settings.saved)
    }

    @Test
    fun `on a phone that cannot run the device check it cannot be turned on, and nothing is written`() = runTest {
        val settings = FakeInteractionSettings()
        val consent = InteractionConsent(settings, supported = false)

        assertFalse(consent.supported)
        assertFalse(consent.set(true))

        assertFalse(consent.allowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
        assertEquals(emptyList<Boolean>(), settings.writes)
    }

    @Test
    fun `a saved yes is ignored on a phone that cannot run the device check`() = runTest {
        val consent = InteractionConsent(FakeInteractionSettings(saved = true), supported = false)

        consent.load()

        assertFalse(consent.allowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `turning it off still works on a phone that cannot run the device check`() = runTest {
        val settings = FakeInteractionSettings(saved = true)
        val consent = InteractionConsent(settings, supported = false)

        assertTrue(consent.set(false))

        assertFalse(settings.saved, "a stale yes is cleared on disk too")
    }

    @Test
    fun `a supported phone behaves as before`() = runTest {
        val consent = InteractionConsent(FakeInteractionSettings(saved = true), supported = true)

        consent.load()

        assertTrue(consent.supported)
        assertSame(CapabilityPolicy.INTERACT, consent.policy())
    }
}
