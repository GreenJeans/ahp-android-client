package dev.ahpclient.app

import dev.ahpclient.auth.Secret
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.SessionStore
import dev.ahpclient.tunnel.TunnelInfo
import java.time.Instant

/** The small, non-secret preference the app remembers between launches. */
interface LastHostStore {
    suspend fun get(): String?

    suspend fun set(tunnelId: String)
}

/** Builds the live connection for a chosen host. [accessToken] is asked for on every (re)connect, so a new sign-in is picked up. */
fun interface HostSessionFactory {
    fun create(tunnel: TunnelInfo, accessToken: suspend () -> Secret): HostSession
}

sealed interface SignInStep {
    data object Idle : SignInStep

    data object RequestingCode : SignInStep

    /** The user types [userCode] at [verificationUri] on any device; this phone just waits for GitHub to say yes. */
    data class AwaitingApproval(val userCode: String, val verificationUri: String, val expiresAt: Instant) : SignInStep

    data class Failed(val message: String) : SignInStep
}

data class HostEntry(val tunnel: TunnelInfo, val isLastUsed: Boolean)

sealed interface HostList {
    data object Loading : HostList

    /** Never empty: an empty result is [Empty]. */
    data class Loaded(val hosts: List<HostEntry>) : HostList

    data object Empty : HostList

    data class Failed(val message: String) : HostList
}

/** What the app is showing, as one value. Screens are a pure function of this. */
sealed interface AppState {
    /** Reading the saved sign-in. */
    data object Restoring : AppState

    /** [notice] explains why the user is back here, for example an expired sign-in. */
    data class SignedOut(val step: SignInStep, val notice: String?) : AppState

    data class Hosts(val list: HostList, val tokenExpiresAt: Instant?) : AppState

    /** [open] is the session whose detail is on screen; its [OpenSession.store] stays subscribed until the user goes back. */
    data class Connected(val host: TunnelInfo, val session: HostSession, val open: OpenSession? = null) : AppState
}

data class OpenSession(val uri: String, val store: SessionStore)

/** Only https pages on GitHub are opened from the sign-in screen; anything else is shown as text, never launched. */
object SafeLinks {
    private val ALLOWED_HOSTS = setOf("github.com")

    fun isSafeVerificationUri(uri: String): Boolean {
        val parsed = try {
            java.net.URI(uri)
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        return parsed.scheme == "https" && parsed.host in ALLOWED_HOSTS && parsed.userInfo == null && parsed.port == -1
    }
}
