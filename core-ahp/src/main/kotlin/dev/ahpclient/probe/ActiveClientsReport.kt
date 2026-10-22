package dev.ahpclient.probe

import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary

/** What the host lists as active clients of one session, and whether this client is among them. */
internal data class ActiveClientsReport(val count: Int, val names: List<String>, val selfListed: Boolean) {
    companion object {
        fun from(state: SessionState, ourClientId: String): ActiveClientsReport {
            val clients = state.activeClients
            return ActiveClientsReport(
                count = clients.size,
                names = clients.map { it.displayName?.takeIf { name -> name.isNotBlank() } ?: "unnamed" },
                selfListed = clients.any { it.clientId == ourClientId },
            )
        }

        /** An exact resource match wins; otherwise the most recently modified session whose title contains [wanted]. */
        fun pick(sessions: List<SessionSummary>, wanted: String): SessionSummary? =
            sessions.firstOrNull { it.resource == wanted }
                ?: sessions.filter { it.title.contains(wanted, ignoreCase = true) }.maxByOrNull { it.modifiedAt }
    }
}
