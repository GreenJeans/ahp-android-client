package dev.ahpclient.app

import dev.ahpclient.ahp.CapabilityPolicy
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** The one saved choice: may this app send messages, answers and approvals as the signed-in user. Off until turned on. */
interface InteractionSettings {
    val enabled: Flow<Boolean>

    /** @throws IOException when the choice cannot be saved. */
    suspend fun setEnabled(on: Boolean)
}

/** Keeps the choice for this process only. The default when nothing persists it, so the answer is always "off" after a restart. */
class InMemoryInteractionSettings : InteractionSettings {
    private val value = MutableStateFlow(false)
    override val enabled: Flow<Boolean> = value

    override suspend fun setEnabled(on: Boolean) {
        value.value = on
    }
}

/**
 * Whether the user has allowed this app to act for them, and the [CapabilityPolicy] that follows from it. Off by
 * default, and anything that goes wrong reads as off: a damaged or unreadable setting never grants the permission.
 *
 * The policy is applied when a connection is made, so a change only takes effect once the session reconnects;
 * [AppController.setInteraction] does that.
 */
class InteractionConsent(private val settings: InteractionSettings = InMemoryInteractionSettings()) {
    private val _allowed = MutableStateFlow(false)
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    /** The tier a new connection should be made under right now. */
    fun policy(): CapabilityPolicy = if (_allowed.value) CapabilityPolicy.INTERACT else CapabilityPolicy.READ

    /** Reads the saved choice. An unreadable one counts as off. */
    suspend fun load() {
        _allowed.value = try {
            settings.enabled.first()
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Turns the permission on or off. Off takes effect at once, before anything is written, so it cannot be held up by
     * a full disk; on takes effect only once the choice is saved, so a consent that could not be recorded is not granted.
     *
     * @return true when the choice was saved. Turning it off always takes effect, whatever this returns.
     */
    suspend fun set(on: Boolean): Boolean {
        if (!on) _allowed.value = false
        return try {
            settings.setEnabled(on)
            if (on) _allowed.value = true
            true
        } catch (_: IOException) {
            false
        }
    }
}
