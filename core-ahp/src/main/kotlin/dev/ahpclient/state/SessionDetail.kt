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
)

/** A working directory as a person reads it: a `file:` URI becomes its decoded path, anything else is shown unchanged. */
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
) {
    /** The chat to show first: the session's main chat, or else the first the host lists. Null while the host lists none. */
    val mainChat: ChatRow? get() = chats.firstOrNull { it.isMain } ?: chats.firstOrNull()

    /** The chats in the order of the tabs: the main chat first, then the others as the host lists them. */
    val tabs: List<ChatRow> get() = mainChat?.let { main -> listOf(main) + chats.filter { it.uri != main.uri } }.orEmpty()

    companion object {
        /** The hint every pending request carries: answering happens in VS Code, never here. */
        const val ANSWER_ELSEWHERE = "Answer in VS Code."

        private const val MAX_ERROR_CHARS = 200
        private const val MAX_CHANGESETS = 20

        /** The host refuses to remove a session's main chat, and a session's only chat is its main one; removing the session is the way. */
        fun canRemoveChat(state: SessionState, chatUri: String): Boolean = chatUri != state.defaultChat && state.chats.size > 1

        fun from(state: SessionState, now: Instant): SessionDetail = SessionDetail(
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
                )
            },
            inputNeeded = state.inputNeeded.orEmpty().map(::describe),
            isArchived = state.status.isArchived,
            isRead = state.status.isRead,
            phase = SessionPhase.of(state.lifecycle.rawValue),
            creationError = state.creationError?.message?.take(MAX_ERROR_CHARS)?.takeIf { it.isNotBlank() },
            changesets = state.changesets.orEmpty().take(MAX_CHANGESETS).map(ChangesetEntry::from),
        )

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
            val headline = input.message?.takeIf { it.isNotBlank() }
                ?: questions.firstNotNullOfOrNull(::questionText)
                ?: "The agent has a question."
            return InputRequestView("Question", headline, questions.map { questionView(it, headline) })
        }

        private fun questionView(question: ChatInputQuestion, headline: String): QuestionView {
            val text = questionText(question)
            val prompt = if (text == null) "A question this app cannot show." else text.takeIf { it != headline }
            return when (question) {
                is ChatInputQuestionText -> QuestionView(prompt, "Free text", emptyList())
                is ChatInputQuestionNumber -> QuestionView(prompt, "Number", emptyList())
                is ChatInputQuestionBoolean -> QuestionView(prompt, "Yes or no", emptyList())
                is ChatInputQuestionSingleSelect -> QuestionView(prompt, "Pick one", question.value.options.orEmpty().map(::optionText))
                is ChatInputQuestionMultiSelect -> QuestionView(prompt, "Pick any", question.value.options.orEmpty().map(::optionText))
                else -> QuestionView(prompt, "Unknown", emptyList())
            }
        }

        private fun optionText(option: ChatInputOption): String = buildString {
            append(option.label.ifBlank { option.id })
            option.description?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            if (option.recommended == true) append(" (recommended)")
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
