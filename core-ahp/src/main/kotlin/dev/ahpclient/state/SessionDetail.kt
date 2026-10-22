package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatInputQuestion
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionBoolean
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionMultiSelect
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionNumber
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionSingleSelect
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionText
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
)

data class ChatRow(
    val title: String,
    val state: ActivityState,
    val activity: String?,
    val whenText: String,
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
) {
    companion object {
        /** The hint every pending request carries: answering happens in VS Code, never here. */
        const val ANSWER_ELSEWHERE = "Answer in VS Code."

        fun from(state: SessionState, now: Instant): SessionDetail = SessionDetail(
            title = state.title.ifBlank { SessionListModel.UNTITLED },
            state = state.status.activityState,
            provider = state.provider,
            activity = state.activity?.takeIf { it.isNotBlank() },
            project = state.project?.displayName?.takeIf { it.isNotBlank() },
            workingDirectories = state.workingDirectories.orEmpty().map(::displayPath),
            chats = state.chats.map { chat ->
                ChatRow(
                    title = chat.title.ifBlank { SessionListModel.UNTITLED },
                    state = chat.status.activityState,
                    activity = chat.activity?.takeIf { it.isNotBlank() },
                    whenText = RelativeTime.format(chat.modifiedAt, now),
                )
            },
            inputNeeded = state.inputNeeded.orEmpty().map(::describe),
            isArchived = state.status.isArchived,
        )

        /** Total: a request kind this client does not know is still shown rather than dropped. */
        fun describe(request: SessionInputRequest): InputRequestView = when (request) {
            is SessionInputRequestChatInput -> InputRequestView("Question", chatInputText(request))
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

        private fun chatInputText(request: SessionInputRequestChatInput): String {
            val input = request.value.request
            val first = input.questions.orEmpty().firstNotNullOfOrNull(::questionText)
            return input.message?.takeIf { it.isNotBlank() } ?: first ?: "The agent has a question."
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

        private fun plain(text: StringOrMarkdown?): String? = when (text) {
            is StringOrMarkdown.Plain -> text.value
            is StringOrMarkdown.Markdown -> text.value
            else -> null
        }
    }
}
