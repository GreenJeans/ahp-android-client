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
