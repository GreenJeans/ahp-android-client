package dev.ahpclient.state

import dev.ahpclient.ahp.AhpException
import dev.ahpclient.ahp.CapabilityDeniedException
import com.microsoft.agenthostprotocol.generated.AgentInfo
import com.microsoft.agenthostprotocol.generated.ResolveSessionConfigResult
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.StateAction
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/** The commands that create and remove sessions and chats, as the manager needs them. [HostSession] is the real one. */
interface ManagementCommands {
    /** [config] is what the host itself resolved for this agent and folder, with the person's choices in it; null leaves every setting to the host. */
    suspend fun createSession(uri: String, provider: String?, workingDirectories: List<String>?, config: Map<String, JsonElement>? = null)

    /** Asks which settings the host would offer for a new session of [provider] in [workingDirectory], given the values already chosen. */
    suspend fun resolveSessionConfig(provider: String, workingDirectory: String, config: Map<String, JsonElement>?): ResolveSessionConfigResult

    suspend fun createChat(sessionUri: String, chatUri: String)

    suspend fun disposeSession(uri: String)

    suspend fun disposeChat(uri: String)
}

/** What happened to one management request. Everything but [Done] and [Unconfirmed] means nothing changed on the host. */
sealed interface ManageResult {
    /** The host took it. [created] is the address of the session or chat that was made, when one was. */
    data class Done(val created: String? = null) : ManageResult

    /** An action was sent but the host did not confirm it: refused, no answer in time, or the connection dropped first. */
    data class Unconfirmed(val outcome: DispatchOutcome) : ManageResult

    /** This connection cannot do it: the tier is too low, or there is no connection. */
    data object NotAllowed : ManageResult

    /** The session or chat is not (or is no longer) there, or is not ready for this. */
    data object NoSuchTarget : ManageResult

    /** The chat is the session's main one (or its only one), which the host never removes on its own: remove the session instead. */
    data object NotRemovable : ManageResult

    /** The agent or folder is not one the host offered. */
    data object InvalidChoice : ManageResult

    /** The person did not pass the device check, so nothing was sent. */
    data object NotConfirmed : ManageResult

    /** The host answered the request with an error. [reason] is its own words: untrusted display text. */
    data class Refused(val code: Int, val reason: String) : ManageResult

    /** Sending failed before anything reached the host. Safe to try again. */
    data class NotSent(val cause: Throwable) : ManageResult
}

/**
 * What the person can do to sessions and chats when they have allowed it: mark a session read or unread, archive it or bring
 * it back, start a chat, start a session, and remove a session or chat. Everything checks first that the connection is on the
 * MANAGE tier and that the target is something the host listed, so a stale screen or a made-up address finds nothing.
 *
 * Starting a session runs an agent on the person's machine, and removing one cannot be undone, so both go through the same
 * device check as approving a tool, looked up again after it (the person took their time, so something else may have changed).
 * Marking read, archiving and starting a chat are reversible or start nothing, and need no check. An agent or folder the
 * host did not offer is never sent: folders come only from sessions the host already lists.
 */
class SessionManager(
    private val allowed: () -> Boolean,
    private val sessions: () -> List<SessionSummary>,
    private val agents: () -> List<AgentInfo>,
    private val sessionState: (String) -> SessionState?,
    private val dispatch: suspend (channel: String, action: StateAction) -> DispatchOutcome,
    private val commands: ManagementCommands,
    private val gate: ApprovalGate,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The agents the host lists, in its order. */
    fun offeredAgents(): List<AgentInfo> = agents()

    /** The folders the person may start a session in: the host's own sessions' folders, most recently used first, without its working copies. See [RecentFolders]. */
    fun offeredFolders(): List<String> = RecentFolders.from(sessions())

    suspend fun setRead(sessionUri: String, read: Boolean): ManageResult =
        action(sessionUri, SessionActions.setRead(read))

    suspend fun setArchived(sessionUri: String, archived: Boolean): ManageResult =
        action(sessionUri, SessionActions.setArchived(archived))

    /** Starts a new, empty chat in a session that is ready. */
    suspend fun newChat(sessionUri: String): ManageResult {
        if (!allowed()) return ManageResult.NotAllowed
        if (!isReady(sessionUri)) return ManageResult.NoSuchTarget
        val chatUri = "$CHAT_SCHEME${newId()}"
        return command { commands.createChat(sessionUri, chatUri) }.let { if (it is ManageResult.Done) ManageResult.Done(chatUri) else it }
    }

    /**
     * Asks the host which settings it offers for a new session of [provider] in [folder], given the [picks] already made. Asked
     * again after each pick, because the answer can change. Only an agent and folder the host offered are ever asked about.
     */
    suspend fun sessionSettings(provider: String, folder: String, picks: Map<String, ConfigPick> = emptyMap()): ConfigOffer {
        if (!allowed()) return ConfigOffer.Unavailable(ManageMessages.describe(ManageResult.NotAllowed).orEmpty())
        if (agents().none { it.provider == provider } || folder !in offeredFolders()) {
            return ConfigOffer.Unavailable(ManageMessages.describe(ManageResult.InvalidChoice).orEmpty())
        }
        return when (val resolved = resolve(provider, folder, picks)) {
            is Resolved.Form ->
                if (resolved.form.fields.isEmpty() && resolved.form.omitted == 0) ConfigOffer.NoSettings else ConfigOffer.Form(resolved.form)
            is Resolved.Failed -> ConfigOffer.Unavailable(ManageMessages.describe(resolved.result) ?: "The host did not answer.")
        }
    }

    /**
     * Starts a session with [provider] in [folder], after the device check. Both must be ones the host offered. With [picks] (the
     * person's choices of settings), the host is asked again first, so every pick is checked against what it offers now and took;
     * the device check then names the settings, so what is confirmed is what is sent. With none, the host keeps all its own settings.
     */
    suspend fun newSession(provider: String, folder: String, picks: Map<String, ConfigPick> = emptyMap()): ManageResult {
        if (!allowed()) return ManageResult.NotAllowed
        val agent = agents().firstOrNull { it.provider == provider } ?: return ManageResult.InvalidChoice
        if (folder !in offeredFolders()) return ManageResult.InvalidChoice
        var config: Map<String, JsonElement>? = null
        var chosen: List<String> = emptyList()
        if (picks.isNotEmpty()) {
            val form = when (val resolved = resolve(provider, folder, picks)) {
                is Resolved.Form -> resolved.form
                is Resolved.Failed -> return resolved.result
            }
            if (!form.accepts(picks) || form.missingRequired(picks).isNotEmpty()) return ManageResult.InvalidChoice
            config = form.wireConfig()
            chosen = form.describe(picks)
        }
        val request = ApprovalRequest(agent.displayName.ifBlank { agent.provider }, SettingsSummary.of(folder, chosen), title = "Start a new session?")
        if (!confirmed(request)) return ManageResult.NotConfirmed
        if (!allowed()) return ManageResult.NotAllowed
        val uri = "$SESSION_SCHEME${newId()}"
        return command { commands.createSession(uri, provider, listOf(folder), config) }.let { if (it is ManageResult.Done) ManageResult.Done(uri) else it }
    }

    private sealed interface Resolved {
        data class Form(val form: SessionConfigForm) : Resolved

        data class Failed(val result: ManageResult) : Resolved
    }

    private suspend fun resolve(provider: String, folder: String, picks: Map<String, ConfigPick>): Resolved =
        try {
            val values = picks.takeIf { it.isNotEmpty() }?.mapValues { it.value.toJson() }
            Resolved.Form(SessionConfigForm.from(commands.resolveSessionConfig(provider, folder, values)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: CapabilityDeniedException) {
            Resolved.Failed(ManageResult.NotAllowed)
        } catch (e: AhpException.Rpc) {
            Resolved.Failed(ManageResult.Refused(e.code, e.message.orEmpty()))
        } catch (e: AhpException) {
            Resolved.Failed(ManageResult.NotSent(e))
        } catch (e: IOException) {
            Resolved.Failed(ManageResult.NotSent(e))
        }

    /** Removes a session and everything in it, after the device check. Cannot be undone; archiving is the way back. */
    suspend fun disposeSession(sessionUri: String): ManageResult {
        if (!allowed()) return ManageResult.NotAllowed
        val summary = sessions().firstOrNull { it.resource == sessionUri } ?: return ManageResult.NoSuchTarget
        if (!confirmed(ApprovalRequest(summary.title.ifBlank { "Untitled session" }, null, title = "Remove this session?"))) return ManageResult.NotConfirmed
        if (!allowed() || sessions().none { it.resource == sessionUri }) return ManageResult.NoSuchTarget
        return command { commands.disposeSession(sessionUri) }
    }

    /** Removes one chat of a session, after the device check. The chat must be listed by that session, and not be its main chat. */
    suspend fun disposeChat(sessionUri: String, chatUri: String): ManageResult {
        if (!allowed()) return ManageResult.NotAllowed
        val state = sessionState(sessionUri) ?: return ManageResult.NoSuchTarget
        val chat = state.chats.firstOrNull { it.resource == chatUri } ?: return ManageResult.NoSuchTarget
        // The host refuses this, but only after it has shut the chat's backend down: never ask it.
        if (!SessionDetail.canRemoveChat(state, chatUri)) return ManageResult.NotRemovable
        if (!confirmed(ApprovalRequest(chat.title.ifBlank { "Untitled chat" }, null, title = "Remove this chat?"))) return ManageResult.NotConfirmed
        // Looked up again after the check: a chat that went away meanwhile is not removed blind.
        if (!allowed() || sessionState(sessionUri)?.chats?.any { it.resource == chatUri } != true) return ManageResult.NoSuchTarget
        return command { commands.disposeChat(chatUri) }
    }

    private suspend fun action(sessionUri: String, action: StateAction): ManageResult {
        if (!allowed()) return ManageResult.NotAllowed
        if (sessions().none { it.resource == sessionUri }) return ManageResult.NoSuchTarget
        return try {
            when (val outcome = dispatch(sessionUri, action)) {
                DispatchOutcome.Accepted -> ManageResult.Done()
                else -> ManageResult.Unconfirmed(outcome)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: CapabilityDeniedException) {
            ManageResult.NotAllowed
        } catch (e: IOException) {
            ManageResult.NotSent(e)
        }
    }

    private suspend fun command(call: suspend () -> Unit): ManageResult =
        try {
            call()
            ManageResult.Done()
        } catch (e: CancellationException) {
            throw e
        } catch (_: CapabilityDeniedException) {
            ManageResult.NotAllowed
        } catch (e: AhpException.Rpc) {
            ManageResult.Refused(e.code, e.message.orEmpty())
        } catch (e: AhpException) {
            ManageResult.NotSent(e)
        } catch (e: IOException) {
            ManageResult.NotSent(e)
        }

    private fun isReady(sessionUri: String): Boolean =
        sessions().any { it.resource == sessionUri } && sessionState(sessionUri)?.lifecycle?.rawValue == READY

    private suspend fun confirmed(request: ApprovalRequest): Boolean =
        try {
            gate.confirm(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    companion object {
        const val SESSION_SCHEME = "ahp-session:/"
        const val CHAT_SCHEME = "ahp-chat:/"
        private const val READY = "ready"
    }
}

/** What the person is told after trying to look after a session, in plain words. Null when there is nothing to say. */
object ManageMessages {
    private const val MAX_HOST_TEXT = 200

    fun describe(result: ManageResult): String? = when (result) {
        is ManageResult.Done -> null
        is ManageResult.Unconfirmed -> InteractMessages.describe(result.outcome)
        ManageResult.NotAllowed -> "This app is not allowed to do that right now."
        ManageResult.NoSuchTarget -> "That is no longer there, or is not ready yet."
        ManageResult.NotRemovable -> "This is the session's main chat, which the host does not remove on its own. Remove the whole session instead."
        ManageResult.InvalidChoice -> "The host does not offer that agent or folder."
        ManageResult.NotConfirmed -> "Not done: the device check was not completed."
        // The host's own words are untrusted display text: bounded, and shown as plain text.
        is ManageResult.Refused -> "The host refused it: ${result.reason.take(MAX_HOST_TEXT)}"
        is ManageResult.NotSent -> "Could not send. Check the connection and try again."
    }

    fun succeeded(result: ManageResult): Boolean = result is ManageResult.Done
}
