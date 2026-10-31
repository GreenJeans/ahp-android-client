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

    /** Takes the saved choice away altogether, for a host that was removed. Writing "off" is the fallback for a store that cannot do better. */
    suspend fun clear() = setEnabled(false)
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
 * [supported] is false on a phone that cannot run the device check in front of tool approvals (Android before 11). There
 * the permission cannot be turned on at all, and a saved "yes" (from a backup, or a phone that was upgraded and rolled
 * back) is ignored: the tier is READ, whatever is stored.
 *
 * The policy is applied when a connection is made, so a change only takes effect once the session reconnects;
 * [AppController.setInteraction] does that.
 */
class InteractionConsent(
    private val settings: InteractionSettings = InMemoryInteractionSettings(),
    val supported: Boolean = true,
    /** The second, separate choice: may it also create, archive and remove sessions and chats. Only ever on while the first is. */
    private val managementSettings: InteractionSettings = InMemoryInteractionSettings(),
    /** Whether the user's Pro entitlement allows write/management tiers; when false, the tier is always [CapabilityPolicy.READ]. */
    private val entitled: () -> Boolean = { true },
) {
    private val _allowed = MutableStateFlow(false)
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    private val _managementAllowed = MutableStateFlow(false)

    /** Whether the user has also allowed this app to look after sessions. Never true while [allowed] is false. */
    val managementAllowed: StateFlow<Boolean> = _managementAllowed.asStateFlow()

    /** The tier a new connection should be made under right now. */
    fun policy(): CapabilityPolicy = when {
        !entitled() || !_allowed.value -> CapabilityPolicy.READ
        _managementAllowed.value -> CapabilityPolicy.MANAGE
        else -> CapabilityPolicy.INTERACT
    }

    /** Reads the saved choices. An unreadable one counts as off, and so does any choice on a phone that is not [supported] or not [entitled]. */
    suspend fun load() {
        _allowed.value = supported && entitled() && read(settings)
        _managementAllowed.value = _allowed.value && read(managementSettings)
    }

    /** Clears active write/management permissions in memory and on disk when Pro entitlement is revoked. */
    suspend fun revokeEntitlement() {
        set(false)
    }

    private suspend fun read(from: InteractionSettings): Boolean = try {
        from.enabled.first()
    } catch (_: IOException) {
        false
    }

    /**
     * Turns the permission on or off. Off takes effect at once, before anything is written, so it cannot be held up by
     * a full disk; on takes effect only once the choice is saved, so a consent that could not be recorded is not granted.
     * On is refused outright, and nothing is written, when the phone is not [supported] or not [entitled]. Turning it off also turns
     * looking after sessions off, so the second permission can never outlive the first.
     *
     * @return true when the choice was saved. Turning it off always takes effect, whatever this returns.
     */
    suspend fun set(on: Boolean): Boolean {
        if (on && (!supported || !entitled())) return false
        if (!on) {
            _allowed.value = false
            _managementAllowed.value = false
            try {
                managementSettings.setEnabled(false)
            } catch (_: IOException) {
                // Off already took effect above; the saved value is also ignored on load while the first choice is off.
            }
        }
        return try {
            settings.setEnabled(on)
            if (on) _allowed.value = true
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Off at once, and both saved choices taken away: for the host these belong to being removed. A failure to remove them is not reported; they read as off either way. */
    suspend fun forget() {
        _allowed.value = false
        _managementAllowed.value = false
        try {
            managementSettings.clear()
            settings.clear()
        } catch (_: IOException) {
            // Off already took effect above, and an entry for a host that no longer exists is never read.
        }
    }

    /**
     * Turns looking after sessions on or off. Refused, with nothing written, unless the app may already act for the user and is [entitled].
     * Off takes effect at once; on only once it is saved.
     *
     * @return true when the choice was saved.
     */
    suspend fun setManagement(on: Boolean): Boolean {
        if (on && (!_allowed.value || !entitled())) return false
        if (!on) _managementAllowed.value = false
        return try {
            managementSettings.setEnabled(on)
            if (on) _managementAllowed.value = true
            true
        } catch (_: IOException) {
            false
        }
    }
}
