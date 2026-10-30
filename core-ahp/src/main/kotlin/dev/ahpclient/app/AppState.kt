package dev.ahpclient.app

import dev.ahpclient.auth.Secret
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.state.ChannelStore
import dev.ahpclient.state.ChatInteractor
import dev.ahpclient.state.ChatStore
import dev.ahpclient.state.DiffResult
import dev.ahpclient.state.ExpansionState
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.SessionStore
import dev.ahpclient.tunnel.TunnelInfo
import com.microsoft.agenthostprotocol.generated.ChangesetState
import com.microsoft.agenthostprotocol.generated.TerminalState
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** The small, non-secret preference the app remembers between launches. */
interface LastHostStore {
    suspend fun get(): String?

    suspend fun set(tunnelId: String)
}

/** Builds the live connection for a chosen host. [accessToken] is asked for on every (re)connect, so a new sign-in is picked up. */
fun interface HostSessionFactory {
    fun create(tunnel: TunnelInfo, accessToken: suspend () -> Secret): HostSession
}

/**
 * Builds the live connection for a host the person added by address. [token] is asked for on every (re)connect and is null for a host that
 * takes none; [consent] is this host's own permission, whose tier the connection is made under. Nothing about the GitHub sign-in is involved.
 */
fun interface DirectSessionFactory {
    fun create(host: DirectHost, token: suspend () -> Secret?, consent: InteractionConsent): HostSession
}

/** A host the app can be connected to: one of the user's Remote Tunnel Access hosts, or one added by its address. [id] is unique across both kinds. */
sealed interface HostRef {
    val id: String
    val name: String

    data class Tunnel(val info: TunnelInfo) : HostRef {
        override val id: String get() = info.tunnelId
        override val name: String get() = info.name
    }

    data class Direct(val host: DirectHost) : HostRef {
        override val id: String get() = host.id
        override val name: String get() = host.name
    }
}

sealed interface SignInStep {
    data object Idle : SignInStep

    data object RequestingCode : SignInStep

    /** The user types [userCode] at [verificationUri] on any device; this phone just waits for GitHub to say yes. */
    data class AwaitingApproval(val userCode: String, val verificationUri: String, val expiresAt: Instant) : SignInStep

    data class Failed(val message: String) : SignInStep
}

data class HostEntry(val tunnel: TunnelInfo, val isLastUsed: Boolean)

/** A host added by address, as the host list shows it. Whether it answers is not known until it is tried. */
data class DirectEntry(val host: DirectHost, val isLastUsed: Boolean)

sealed interface HostList {
    data object Loading : HostList

    /** Never empty: an empty result is [Empty]. */
    data class Loaded(val hosts: List<HostEntry>) : HostList

    data object Empty : HostList

    data class Failed(val message: String) : HostList

    /** Not signed in to GitHub, so Remote Tunnel Access hosts are not listed. Hosts added by address still are (see [HostCatalog.direct]). */
    data object NotSignedIn : HostList
}

/**
 * The hosts that can be connected to, kept apart from [AppState] so that they can be listed while a host is connected too.
 * [list] is the tunnel hosts of the signed-in account; [direct] the hosts added by address, which are listed whether or not anyone is
 * signed in and whether or not [list] loaded. [tokenExpiresAt] is when the current access token runs out; with [renews] the app trades
 * it for a new one on its own before then. [autoConnectPending] is true while the cold start is still trying to connect to the last host
 * by itself (while [list] loads or is retried after a failure).
 */
data class HostCatalog(
    val list: HostList,
    val direct: List<DirectEntry> = emptyList(),
    val tokenExpiresAt: Instant? = null,
    val renews: Boolean = false,
    val autoConnectPending: Boolean = false,
)

/** What the app is showing, as one value. Screens are a pure function of this. */
sealed interface AppState {
    /** Reading the saved sign-in. */
    data object Restoring : AppState

    /** [notice] explains why the user is back here, for example an expired sign-in. [offerRevoke] is set only after the user signed out on purpose. */
    data class SignedOut(val step: SignInStep, val notice: String?, val offerRevoke: Boolean = false) : AppState

    /** Signed in, or holding hosts added by address, with no host connected. The hosts to choose from are [AppController.hosts]. */
    data object Hosts : AppState

    /** [open] is the session whose detail is on screen; its [OpenSession.store] stays subscribed until the user goes back. */
    data class Connected(val host: HostRef, val session: HostSession, val open: OpenSession? = null) : AppState {
        constructor(tunnel: TunnelInfo, session: HostSession, open: OpenSession? = null) : this(HostRef.Tunnel(tunnel), session, open)
    }
}

/**
 * [chat] is the chat on screen. [warm] holds the session's other chats that were looked at, which stay subscribed so that going back
 * to one is instant instead of a reload; they are released with the session.
 */
data class OpenSession(val uri: String, val store: SessionStore, val chat: OpenChat? = null, val changeset: OpenChangeset? = null, val terminal: OpenTerminal? = null, val warm: List<OpenChat> = emptyList())

/** The live output of one terminal on screen, over the chat it was opened from. Its [store] stays subscribed until the user goes back. */
data class OpenTerminal(val uri: String, val store: ChannelStore<TerminalState>)

/**
 * A changeset on screen: the files that changed. Its [store] stays subscribed until the user goes back. [reviewable] is the
 * host's own statement that files in it can be marked reviewed.
 */
data class OpenChangeset(val uri: String, val store: ChannelStore<ChangesetState>, val reviewable: Boolean = false, val diff: OpenDiff? = null)

/** What loading one file's changes is doing: still going, or how it ended. */
sealed interface DiffLoad {
    data object Loading : DiffLoad

    data class Loaded(val result: DiffResult) : DiffLoad
}

/** One file's line-by-line changes on screen, over its changeset. [path] is how the file is named; [load] follows the loading. */
data class OpenDiff(val fileId: String, val path: String, val load: StateFlow<DiffLoad>)

/**
 * The chat whose transcript is on screen; its [store] stays subscribed until the user goes back. [expansion] outlives the
 * screen. [interactor] is what the person can do in it; it checks the connection's tier on every call, so a screen may
 * keep one while consent changes underneath it. [parent] is the chat this one was opened from when it is a subagent's
 * transcript: going back returns there, and the parent stays subscribed meanwhile.
 */
data class OpenChat(val uri: String, val store: ChatStore, val expansion: ExpansionState, val interactor: ChatInteractor, val parent: OpenChat? = null)

/** Where a GitHub user removes an authorized app. Signing out here only forgets the token; this removes the grant itself. */
const val GITHUB_AUTHORIZED_APPS_URL = "https://github.com/settings/applications"

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

    /**
     * Links inside an agent's output are untrusted text, so the only ones ever launched are plain https pages with a real
     * host name and no embedded credentials. A host that java.net.URI cannot parse as a server name (for example one written
     * with look-alike Unicode letters) is refused rather than shown, so a lookalike cannot pass for a familiar site.
     */
    fun isSafeWebLink(uri: String): Boolean {
        if (uri.length > MAX_LINK_LENGTH) return false
        val parsed = try {
            java.net.URI(uri)
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        val host = parsed.host ?: return false
        return parsed.scheme == "https" && host.isNotBlank() && parsed.userInfo == null && host.all { it.code in ASCII_PRINTABLE }
    }

    private const val MAX_LINK_LENGTH = 2_048
    private val ASCII_PRINTABLE = 0x21..0x7e
}
