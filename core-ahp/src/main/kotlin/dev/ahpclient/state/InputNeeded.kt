package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatInputQuestionText
import com.microsoft.agenthostprotocol.generated.ConfirmationOptionKind
import com.microsoft.agenthostprotocol.generated.SessionInputRequestChatInput
import com.microsoft.agenthostprotocol.generated.SessionInputRequest
import com.microsoft.agenthostprotocol.generated.SessionInputRequestToolConfirmation
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.ToolCallConfirmationStatePendingConfirmation
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One of the host's own options on the waiting tool call, as a notification button. [label] is the host's text, so it is
 * untrusted: plain, one line and short. [approves] says which way it goes; it is checked again against the live chat when
 * the button is pressed, and an option that now means the opposite, or is gone, is never sent.
 */
data class DecisionOption(val id: String, val label: String, val approves: Boolean)

/**
 * The one tool call a notification's buttons act on: which chat it is in, and which call. With no [options] the buttons are
 * a plain Approve and Deny. When the host offered options of its own, they are the buttons, in the host's words, with the
 * refusal first. It is only ever made by [from], and acting on it still goes through everything an approval on screen goes through.
 */
data class ApprovalTarget(val chat: String, val toolCallId: String, val options: List<DecisionOption> = emptyList()) {
    companion object {
        /** No chat address is longer than this; a longer one is never subscribed to. */
        const val MAX_CHAT_URI = 512

        /** A notification has room for three buttons. */
        const val MAX_BUTTONS = 3

        /** The longest button text; a longer label is cut. */
        const val MAX_LABEL = 28

        /** The longest option id accepted; a longer one is not offered and not decided. */
        const val MAX_OPTION_ID = 200

        /**
         * The call a notification may offer to decide, or null when it may not. That is when the session is waiting on exactly
         * one thing, the thing is a tool call itself (not its output), and the chat it is in is one the session lists. When the
         * host offered options, the buttons are its first refusal and then its approvals, at most [MAX_BUTTONS]; the rest are
         * decided in the app, where the person can see them all. Anything else is decided in the app too.
         */
        fun from(state: SessionState): ApprovalTarget? {
            val only = state.inputNeeded.orEmpty().singleOrNull() as? SessionInputRequestToolConfirmation ?: return null
            val call = only.value.toolCall as? ToolCallConfirmationStatePendingConfirmation ?: return null
            val chat = only.value.chat
            if (chat.isBlank() || chat.length > MAX_CHAT_URI || state.chats.none { it.resource == chat }) return null
            val offered = call.value.options.orEmpty()
            if (offered.isEmpty()) return ApprovalTarget(chat, call.value.toolCallId)
            val usable = offered
                .filter { it.id.isNotBlank() && it.id.length <= MAX_OPTION_ID }
                .distinctBy { it.id }
                .map { option ->
                    val approves = option.kind == ConfirmationOptionKind.APPROVE
                    DecisionOption(option.id, label(option.label, approves), approves)
                }
            val refusal = usable.firstOrNull { !it.approves }
            val approvals = usable.filter { it.approves }.take(MAX_BUTTONS - if (refusal != null) 1 else 0)
            val buttons = listOfNotNull(refusal) + approvals
            return if (buttons.isEmpty()) null else ApprovalTarget(chat, call.value.toolCallId, buttons)
        }

        private fun label(raw: String, approves: Boolean): String {
            val oneLine = raw.replace(Regex("\\s+"), " ").trim().ifEmpty { if (approves) "Approve" else "Deny" }
            return if (oneLine.length <= MAX_LABEL) oneLine else oneLine.take(MAX_LABEL - 1).trimEnd() + "…"
        }
    }
}

/**
 * A session that has just started waiting on the user. [detail] is what it is waiting for (the question, or the tool to
 * approve) when that could be read in time; null means only the session [title] is known. [approval] is set when what it is
 * waiting for is one plain tool call that the notification may offer to decide.
 */
/**
 * The one free-text question a notification may offer to answer in place: which chat it is in, and which request. It is only
 * ever made by [from], and answering it still checks the live chat's form rules and capability tier before anything is sent.
 */
data class QuestionTarget(val chat: String, val requestId: String) {
    companion object {
        /** The longest request or question id accepted; a longer one is not offered and not answered from a notification. */
        const val MAX_REQUEST_ID = 200

        /** The longest answer accepted from a notification's text field. */
        const val MAX_ANSWER_CHARS = 4_000

        /**
         * The question a notification may offer to answer, or null when it may not. That is when the session is waiting on
         * exactly one thing, the thing is a chat input request with a single free-text question, and the chat it is in is
         * one the session lists. A choice, number, yes/no or multi-question form is answered in the app instead.
         */
        fun from(state: SessionState): QuestionTarget? {
            val only = state.inputNeeded.orEmpty().singleOrNull() as? SessionInputRequestChatInput ?: return null
            val chat = only.value.chat
            if (chat.isBlank() || chat.length > ApprovalTarget.MAX_CHAT_URI || state.chats.none { it.resource == chat }) return null
            val request = only.value.request
            if (request.id.isBlank() || request.id.length > MAX_REQUEST_ID) return null
            val question = request.questions.orEmpty().singleOrNull() as? ChatInputQuestionText ?: return null
            if (question.value.id.isBlank() || question.value.id.length > MAX_REQUEST_ID) return null
            return QuestionTarget(chat, request.id)
        }
    }
}

data class InputNeeded(
    val resource: String,
    val title: String,
    val detail: String? = null,
    val approval: ApprovalTarget? = null,
    val question: QuestionTarget? = null,
)

/** What a notification says a session is waiting for: the one thing, or, with several, how many and what the first is. */
object WaitingText {
    fun of(requests: List<SessionInputRequest>): String? {
        val views = requests.map { SessionDetail.describe(it) }
        val first = views.firstOrNull()?.text ?: return null
        if (views.size == 1) return first
        // More than one is decided in the app, where each can be seen: the words say so, since there are no buttons to act on.
        val what = if (views.all { it.kind == "Approval" }) "approvals" else "requests"
        return "${views.size} $what waiting. First: $first. Open the app to decide."
    }
}

/** What reading a waiting session gave: its words, and the tool call to offer buttons for, if there is exactly one. */
data class RequestRead(val text: String?, val approval: ApprovalTarget? = null, val question: QuestionTarget? = null)

/**
 * Finds the sessions that have begun waiting on the user between one list and the next. The first list only records
 * who is already waiting, so connecting to a host that has open questions does not notify for all of them; after
 * that, a session is reported once when it enters the state and again only after it has left and re-entered.
 */
class InputNeededDetector {
    private val waiting = HashSet<String>()
    private var seeded = false

    fun onSessions(sessions: List<SessionSummary>): List<InputNeeded> {
        val nowWaiting = sessions.filter { it.status.activityState == ActivityState.NEEDS_INPUT }
        val started = if (seeded) nowWaiting.filter { it.resource !in waiting } else emptyList()
        waiting.clear()
        nowWaiting.forEach { waiting += it.resource }
        seeded = true
        return started.map { InputNeeded(it.resource, it.title.ifBlank { SessionListModel.UNTITLED }) }
    }
}

/**
 * Turns session lists into notifications. A session must still be waiting [debounceMs] after it started, so a question
 * that is answered or withdrawn straight away does not buzz the phone. The session list does not say what the session is
 * waiting for, so after the debounce [readDetail] is asked for it; it gets [readTimeoutMs], and if it is slow, fails or
 * has nothing, the alert goes out with the session title alone rather than not at all. [resolved] is called when a
 * session that was notified stops waiting, so a stale notification can be removed.
 */
class InputNeededNotifier(
    private val sessions: Flow<List<SessionSummary>>,
    private val notify: (InputNeeded) -> Unit,
    private val resolved: (String) -> Unit = {},
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val readDetail: suspend (String) -> RequestRead? = { null },
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
) {
    private val detector = InputNeededDetector()
    private val pending = ConcurrentHashMap<String, Job>()
    private val notified = ConcurrentHashMap.newKeySet<String>()

    /** Runs until cancelled. */
    suspend fun run() = coroutineScope {
        sessions.collect { list ->
            val waiting = list.filter { it.status.activityState == ActivityState.NEEDS_INPUT }.map { it.resource }.toSet()
            pending.keys.filter { it !in waiting }.forEach { pending.remove(it)?.cancel() }
            notified.filter { it !in waiting }.forEach { uri ->
                notified.remove(uri)
                resolved(uri)
            }
            detector.onSessions(list).forEach { started ->
                pending[started.resource] = launch {
                    delay(debounceMs)
                    // The job stays registered while the question is read, so a session that stops waiting meanwhile cancels it.
                    val read = read(started.resource)
                    pending.remove(started.resource)
                    notified += started.resource
                    notify(started.copy(detail = read?.text, approval = read?.approval, question = read?.question))
                }
            }
        }
    }

    private suspend fun read(resource: String): RequestRead? = try {
        withTimeoutOrNull(readTimeoutMs) { readDetail(resource) }?.let { RequestRead(it.text?.let(::tidy), it.approval, it.question) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 3_000L
        const val DEFAULT_READ_TIMEOUT_MS = 4_000L
        const val MAX_DETAIL_CHARS = 240

        /** One line of plain text, short enough for a notification. Null when nothing readable is left. */
        fun tidy(text: String): String? {
            val oneLine = text.replace(Regex("\\s+"), " ").trim()
            if (oneLine.isEmpty()) return null
            return if (oneLine.length <= MAX_DETAIL_CHARS) oneLine else oneLine.take(MAX_DETAIL_CHARS - 1).trimEnd() + "…"
        }
    }
}

/** The persistent notification's words while monitoring is on. */
data class MonitorStatus(val title: String, val text: String) {
    companion object {
        fun from(host: String, sessions: List<SessionSummary>, connection: ConnState): MonitorStatus {
            val title = "Monitoring $host"
            val text = when (connection) {
                ConnState.Online -> {
                    val active = sessions.filter { !it.status.isArchived }
                    val waiting = active.count { it.status.activityState == ActivityState.NEEDS_INPUT }
                    when {
                        active.isEmpty() -> "No active sessions"
                        waiting > 0 -> "${active.size} active ${plural(active.size)} · $waiting need${if (waiting == 1) "s" else ""} input"
                        else -> "${active.size} active ${plural(active.size)}"
                    }
                }
                ConnState.Connecting -> "Connecting…"
                is ConnState.Reconnecting -> "Connection lost. Reconnecting…"
                ConnState.WaitingForNetwork -> "Waiting for network"
                is ConnState.Failed -> "Disconnected. Open the app for details."
                ConnState.Stopped -> "Stopped"
            }
            return MonitorStatus(title, text)
        }

        private fun plural(count: Int) = if (count == 1) "session" else "sessions"
    }
}

/** What the Quick Settings tile shows: active while monitoring runs, with the inbox's waiting count when online. */
data class MonitorTileState(val active: Boolean, val label: String, val subtitle: String) {
    companion object {
        const val LABEL = "AHP Monitor"
        const val OFF_SUBTITLE = "Monitoring off"

        fun from(status: MonitorStatus?, sessions: List<SessionSummary>, online: Boolean): MonitorTileState {
            if (status == null) return MonitorTileState(active = false, label = LABEL, subtitle = OFF_SUBTITLE)
            if (!online) return MonitorTileState(active = true, label = LABEL, subtitle = status.text)
            val waiting = sessions.count { !it.status.isArchived && it.status.activityState == ActivityState.NEEDS_INPUT }
            val subtitle = if (waiting > 0) "$waiting waiting" else "No sessions waiting"
            return MonitorTileState(active = true, label = LABEL, subtitle = subtitle)
        }
    }
}
