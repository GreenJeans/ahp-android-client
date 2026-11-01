package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatInputOption
import com.microsoft.agenthostprotocol.generated.ChatInputQuestion
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionBoolean
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionMultiSelect
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionNumber
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionSingleSelect
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionText
import com.microsoft.agenthostprotocol.generated.ChatInputRequest
import com.microsoft.agenthostprotocol.generated.SessionInputRequest
import com.microsoft.agenthostprotocol.generated.SessionInputRequestChatInput
import com.microsoft.agenthostprotocol.generated.SessionInputRequestToolAuthentication
import com.microsoft.agenthostprotocol.generated.SessionInputRequestToolClientExecution
import com.microsoft.agenthostprotocol.generated.SessionInputRequestToolConfirmation
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.StringOrMarkdown
import com.microsoft.agenthostprotocol.generated.ToolCallConfirmationState
import com.microsoft.agenthostprotocol.generated.ToolCallConfirmationStatePendingConfirmation
import com.microsoft.agenthostprotocol.generated.ToolCallConfirmationStatePendingResultConfirmation
import java.time.Instant

/** What the agent is waiting on, in words. This client is read-only, so it only ever describes the request. */
data class InputRequestView(
    val kind: String,
    val text: String,
    val questions: List<QuestionView> = emptyList(),
)

/**
 * One question of a chat request. [prompt] is null when it is already the request's headline, so the screen does not
 * say it twice. [options] holds the choices of a pick-one or pick-any question, so the user can tell from the phone what
 * the agent is asking before going to VS Code to answer.
 */
data class QuestionView(
    val prompt: String?,
    val answerKind: String,
    val options: List<String>,
    val heading: String? = null,
)

/** Where a session is in starting up, as the host reports it. A word this client does not know is [OTHER], never treated as ready. */
enum class SessionPhase {
    CREATING,
    READY,
    FAILED,
    OTHER,
    ;

    companion object {
        fun of(raw: String): SessionPhase = when (raw) {
            "creating" -> CREATING
            "ready" -> READY
            "failed" -> FAILED
            else -> OTHER
        }
    }
}

data class ChatRow(
    val uri: String,
    val title: String,
    val state: ActivityState,
    val activity: String?,
    val whenText: String,
    /** False for the session's main chat (and for a session's only chat): the host never removes those on its own. */
    val removable: Boolean = true,
    /** The session's main chat, the one it was started with. */
    val isMain: Boolean = false,
    val isArchived: Boolean = false,
    val isRead: Boolean = false,
)

/** A working directory as a person reads it: a `file:` URI becomes its decoded path, anything else is shown unchanged. */
/** Summary of annotations attached to a session (`SessionState.annotations` or `SessionSummary.annotations`). */
data class AnnotationsSummaryView(
    val resource: String,
    val annotationCount: Long,
    val entryCount: Long,
) {
    val summaryText: String
        get() = buildList {
            add("$annotationCount ${if (annotationCount == 1L) "annotation" else "annotations"}")
            if (entryCount > 0L) add("$entryCount ${if (entryCount == 1L) "entry" else "entries"}")
        }.joinToString(" · ")
}

fun displayPath(uri: String): String {
    val parsed = try {
        java.net.URI(uri)
    } catch (_: java.net.URISyntaxException) {
        return uri
    }
    val path = parsed.path ?: return uri
    if (parsed.scheme != "file") return uri
    // A Windows drive path arrives as /c:/Users/..., and the leading slash is noise.
    return if (Regex("^/[A-Za-z]:").containsMatchIn(path)) path.substring(1) else path
}

data class SessionDetail(
    val title: String,
    val state: ActivityState,
    val provider: String,
    val activity: String?,
    val project: String?,
    val workingDirectories: List<String>,
    val chats: List<ChatRow>,
    val inputNeeded: List<InputRequestView>,
    val isArchived: Boolean,
    val isRead: Boolean = false,
    /** Whether the host has finished starting the session. Chats can only be added once it is [SessionPhase.READY]. */
    val phase: SessionPhase = SessionPhase.READY,
    /** Why starting failed, in the host's own words: untrusted display text, already clipped. */
    val creationError: String? = null,
    /** The changesets the session advertises. One whose [ChangesetEntry.uri] is null needs values filled in and is not opened here. */
    val changesets: List<ChangesetEntry> = emptyList(),
    /** What the host says about the session's git state (E2); [SessionExtras.NONE] for a host that says nothing. */
    val extras: SessionExtras = SessionExtras.NONE,
    /** The pull requests the host links to this session (E2), validated to HTTPS URLs only. */
    val pullRequests: List<PullRequest> = extras.pullRequests,
    /** The model id the host reports for this session (E6), or null when the host reports none. */
    val model: String? = extras.model,
    /** Summary of annotations attached to this session, or null when the host reports none. */
    val annotations: AnnotationsSummaryView? = null,
) {
    /** The chat to show first: the session's main chat, or else the first the host lists. Null while the host lists none. */
    val mainChat: ChatRow? get() = chats.firstOrNull { it.isMain } ?: chats.firstOrNull()

    /** The chats in the order of the tabs: the main chat first, then the others as the host lists them. */
    val tabs: List<ChatRow> get() = mainChat?.let { main -> listOf(main) + chats.filter { it.uri != main.uri } }.orEmpty()

    /** How many non-main chats in this session are archived. */
    val archivedChatCount: Int get() = chats.count { it.isArchived && !it.isMain }

    /** The tabs to show on screen: archived non-main chats are hidden unless [showArchived] is true. */
    fun visibleTabs(showArchived: Boolean = false): List<ChatRow> =
        tabs.filter { showArchived || !it.isArchived || it.isMain }

    companion object {
        /** The hint every pending request carries: answering happens in VS Code, never here. */
        const val ANSWER_ELSEWHERE = "Answer in VS Code."

        private const val MAX_ERROR_CHARS = 200
        private const val MAX_CHANGESETS = 20

        /** The host refuses to remove a session's main chat, and a session's only chat is its main one; removing the session is the way. */
        fun canRemoveChat(state: SessionState, chatUri: String): Boolean = chatUri != state.defaultChat && state.chats.size > 1

        fun from(state: SessionState, now: Instant, summary: SessionSummary? = null): SessionDetail {
            val stateExtras = state.extras
            val summaryExtras = summary?.extras ?: SessionExtras.NONE
            val mergedExtras = SessionExtras(
                branch = stateExtras.branch ?: summaryExtras.branch,
                outgoing = stateExtras.outgoing ?: summaryExtras.outgoing,
                incoming = stateExtras.incoming ?: summaryExtras.incoming,
                uncommitted = stateExtras.uncommitted ?: summaryExtras.uncommitted,
                detachedHead = stateExtras.detachedHead || summaryExtras.detachedHead,
                pullRequests = stateExtras.pullRequests.ifEmpty { summaryExtras.pullRequests },
                model = stateExtras.model ?: summaryExtras.model,
            )
            return SessionDetail(
                title = state.title.ifBlank { SessionListModel.UNTITLED },
                state = state.status.activityState,
                provider = state.provider,
                activity = state.activity?.takeIf { it.isNotBlank() },
                project = state.project?.displayName?.takeIf { it.isNotBlank() },
                workingDirectories = state.workingDirectories.orEmpty().map(::displayPath),
                chats = state.chats.map { chat ->
                    ChatRow(
                        uri = chat.resource,
                        title = chat.title.ifBlank { SessionListModel.UNTITLED },
                        state = chat.status.activityState,
                        activity = chat.activity?.takeIf { it.isNotBlank() },
                        whenText = RelativeTime.format(chat.modifiedAt, now),
                        removable = canRemoveChat(state, chat.resource),
                        isMain = chat.resource == state.defaultChat,
                        isArchived = chat.status.isArchived || summary?.chats?.firstOrNull { it.resource == chat.resource }?.status?.isArchived == true,
                        isRead = chat.status.isRead,
                    )
                },
                inputNeeded = state.inputNeeded.orEmpty().map(::describe),
                isArchived = state.status.isArchived,
                isRead = state.status.isRead,
                phase = SessionPhase.of(state.lifecycle.rawValue),
                creationError = state.creationError?.message?.take(MAX_ERROR_CHARS)?.takeIf { it.isNotBlank() },
                changesets = state.changesets.orEmpty().take(MAX_CHANGESETS).map(ChangesetEntry::from),
                extras = mergedExtras,
                pullRequests = mergedExtras.pullRequests,
                model = mergedExtras.model,
                annotations = (state.annotations ?: summary?.annotations)?.takeIf { it.annotationCount > 0L || it.entryCount > 0L }?.let {
                    AnnotationsSummaryView(resource = it.resource, annotationCount = it.annotationCount, entryCount = it.entryCount)
                },
            )
        }

        /** Total: a request kind this client does not know is still shown rather than dropped. */
        fun describe(request: SessionInputRequest): InputRequestView = when (request) {
            is SessionInputRequestChatInput -> chatInputView(request)
            is SessionInputRequestToolConfirmation -> InputRequestView("Approval", toolText(request.value.toolCall))
            is SessionInputRequestToolClientExecution -> {
                val call = request.value.toolCall
                InputRequestView("Tool run", call.displayName.ifBlank { call.toolName })
            }
            is SessionInputRequestToolAuthentication -> {
                val call = request.value.toolCall
                InputRequestView("Sign-in", call.displayName.ifBlank { call.toolName })
            }
            else -> InputRequestView("Input", "The agent is waiting for input.")
        }

        private fun chatInputView(request: SessionInputRequestChatInput): InputRequestView = describeChatInput(request.value.request)

        /** A question the agent asked in a chat, in words; shared by the session detail and the transcript. */
        fun describeChatInput(input: ChatInputRequest): InputRequestView {
            val questions = input.questions.orEmpty()
            val headline = input.message?.takeIf { it.isNotBlank() && it != "The agent has a question" }
                ?: questions.firstNotNullOfOrNull(::questionText)
                ?: "The agent has a question."
            return InputRequestView("Question", headline, questions.map { questionView(it, headline) })
        }

        private fun questionView(question: ChatInputQuestion, headline: String): QuestionView {
            val text = questionText(question)
            val prompt = if (text == null) "A question this app cannot show." else text.takeIf { it != headline }
            val heading = questionHeading(question, headline)
            return when (question) {
                is ChatInputQuestionText -> QuestionView(prompt, "Free text", emptyList(), heading)
                is ChatInputQuestionNumber -> QuestionView(prompt, "Number", emptyList(), heading)
                is ChatInputQuestionBoolean -> QuestionView(prompt, "Yes or no", emptyList(), heading)
                is ChatInputQuestionSingleSelect -> QuestionView(prompt, "Pick one", question.value.options.orEmpty().map(::optionText), heading)
                is ChatInputQuestionMultiSelect -> QuestionView(prompt, "Pick any", question.value.options.orEmpty().map(::optionText), heading)
                else -> QuestionView(prompt, "Unknown", emptyList(), heading)
            }
        }

        private fun optionText(option: ChatInputOption): String = buildString {
            append(option.label.ifBlank { option.id })
            option.description?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            if (option.recommended == true) append(" (recommended)")
        }

        private fun questionHeading(question: ChatInputQuestion, headline: String): String? {
            val (title, message) = when (question) {
                is ChatInputQuestionText -> question.value.run { title to message }
                is ChatInputQuestionSingleSelect -> question.value.run { title to message }
                is ChatInputQuestionMultiSelect -> question.value.run { title to message }
                is ChatInputQuestionNumber -> question.value.run { title to message }
                is ChatInputQuestionBoolean -> question.value.run { title to message }
                else -> return null
            }
            return title?.takeIf { it.isNotBlank() && message.isNotBlank() && it != message && it != headline }
        }

        private fun questionText(question: ChatInputQuestion): String? = when (question) {
            is ChatInputQuestionText -> question.value.run { message.ifBlank { title } }
            is ChatInputQuestionSingleSelect -> question.value.run { message.ifBlank { title } }
            is ChatInputQuestionMultiSelect -> question.value.run { message.ifBlank { title } }
            is ChatInputQuestionNumber -> question.value.run { message.ifBlank { title } }
            is ChatInputQuestionBoolean -> question.value.run { message.ifBlank { title } }
            else -> null
        }?.takeIf { it.isNotBlank() }

        private fun toolText(call: ToolCallConfirmationState): String {
            val (name, intention, message) = when (call) {
                is ToolCallConfirmationStatePendingConfirmation -> call.value.run { Triple(displayName.ifBlank { toolName }, this.intention, invocationMessage) }
                is ToolCallConfirmationStatePendingResultConfirmation -> call.value.run { Triple(displayName.ifBlank { toolName }, this.intention, invocationMessage) }
                else -> return "A tool needs approval."
            }
            val detail = intention?.takeIf { it.isNotBlank() } ?: plain(message)
            return if (detail.isNullOrBlank()) name else "$name: $detail"
        }

        internal fun plain(text: StringOrMarkdown?): String? = when (text) {
            is StringOrMarkdown.Plain -> MarkdownPlain.of(text.value)
            is StringOrMarkdown.Markdown -> MarkdownPlain.of(text.value)
            else -> null
        }
    }
}
