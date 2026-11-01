package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ChildCustomization
import com.microsoft.agenthostprotocol.generated.ChildCustomizationAgent
import com.microsoft.agenthostprotocol.generated.CustomizationDirectory
import com.microsoft.agenthostprotocol.generated.CustomizationEnablement
import com.microsoft.agenthostprotocol.generated.CustomizationPlugin
import com.microsoft.agenthostprotocol.generated.RootState
import com.microsoft.agenthostprotocol.generated.SessionState

/** A custom agent (an agent file such as `plan.agent.md`) the person may pick for a message. [name] and [description] are the host's words, bounded. */
data class AgentChoice(val uri: String, val name: String, val description: String?)

/**
 * What a message says about the custom agent. The host applies the agent a message names on every turn, and a message that names
 * none puts the chat back on the provider's own behaviour, so "say nothing" is never the same as "keep what it has": [Keep] is the
 * one that leaves the chat as it is, and it is what a message carries unless the person picked something.
 */
sealed interface AgentPick {
    /** The agent the chat is on now, so sending changes nothing. */
    data object Keep : AgentPick

    /** No custom agent. */
    data object None : AgentPick

    /** One of the agents the host offers. */
    data class Use(val uri: String) : AgentPick
}

/** The custom agents a session offers, and which one a chat is on. Pure, so it is tested without a phone. */
object CustomAgents {
    const val MAX_AGENTS = 100
    private const val MAX_NAME = 80
    private const val MAX_DESCRIPTION = 200

    /**
     * The agents the person may pick: those the session's customizations list under a plugin or a folder that is turned on, that are
     * not turned off themselves, and that the host has not reserved for its own delegation. In the order the host lists them, each once.
     */
    fun of(session: SessionState?, root: RootState? = null): List<AgentChoice> =
        SlashCommands.combinedCustomizations(session, root)
            .flatMap { container ->
                when (container) {
                    is CustomizationPlugin -> if (enabled(container.value.enablement)) agentsIn(container.value.children) else emptyList()
                    is CustomizationDirectory -> if (container.value.enabled) agentsIn(container.value.children) else emptyList()
                    else -> emptyList()
                }
            }
            .distinctBy { it.uri }
            .take(MAX_AGENTS)

    /**
     * The agent a chat is on: the one its latest message was sent with. A latest message that names none means none, because that is
     * what the host does with it; only a chat with no message yet falls back to the agent in its draft. Null is "no custom agent".
     */
    fun inUse(chat: ChatState?): String? {
        chat ?: return null
        val latest = chat.activeTurn?.message ?: chat.turns.lastOrNull()?.message
        val selection = if (latest != null) latest.agent else chat.draft?.agent
        return selection?.uri?.takeIf { it.isNotBlank() }
    }

    /** What to call [uri]: its name in [offered], else the name of its file; null for no agent. */
    fun nameOf(uri: String?, offered: List<AgentChoice>): String? {
        if (uri.isNullOrBlank()) return null
        offered.firstOrNull { it.uri == uri }?.let { return it.name }
        val file = uri.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return file.removeSuffix(".md").removeSuffix(".agent").ifBlank { uri }.take(MAX_NAME)
    }

    private fun agentsIn(children: List<ChildCustomization>?): List<AgentChoice> =
        children.orEmpty()
            .mapNotNull { (it as? ChildCustomizationAgent)?.value }
            .filter { it.enabled != false && it.disableUserInvocation != true && it.uri.isNotBlank() }
            .map { agent ->
                AgentChoice(
                    agent.uri,
                    agent.name.ifBlank { nameOf(agent.uri, emptyList()).orEmpty() }.take(MAX_NAME),
                    agent.description?.takeIf { it.isNotBlank() }?.take(MAX_DESCRIPTION),
                )
            }

    /** The host lists the decisions most specific first; no decision means on. */
    private fun enabled(decisions: List<CustomizationEnablement>?): Boolean =
        when (val first = decisions?.firstOrNull()) {
            is CustomizationEnablement.Session -> first.value.enabled
            is CustomizationEnablement.Workspace -> first.value.enabled
            is CustomizationEnablement.Global -> first.value.enabled
            else -> true
        }
}
