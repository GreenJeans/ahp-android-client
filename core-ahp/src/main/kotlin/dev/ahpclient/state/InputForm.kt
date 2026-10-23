package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatInputOption
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionBoolean
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionMultiSelect
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionNumber
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionSingleSelect
import com.microsoft.agenthostprotocol.generated.ChatInputQuestionText
import com.microsoft.agenthostprotocol.generated.ChatInputRequest

enum class QuestionKind { TEXT, NUMBER, BOOLEAN, SINGLE, MULTI }

data class FormOption(val id: String, val label: String)

/** One question as a form field. [min] and [max] bound a text's length, a number's value, or how many options may be picked. */
data class FormQuestion(
    val id: String,
    val title: String,
    val kind: QuestionKind,
    val required: Boolean,
    val options: List<FormOption> = emptyList(),
    val allowFreeform: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val defaultText: String? = null,
    val defaultBool: Boolean? = null,
)

/** What the person has entered for one question. */
sealed interface FormValue {
    data class Text(val text: String) : FormValue

    data class Bool(val value: Boolean) : FormValue

    /** One option, or text typed instead when the question allows it. [optionId] is null when none is picked. */
    data class Choice(val optionId: String?, val freeform: String = "") : FormValue

    data class Choices(val optionIds: Set<String>, val freeform: String = "") : FormValue
}

/** Whether a set of entered values is ready to send. */
sealed interface FormCheck {
    data class Ready(val answers: Map<String, AnswerValue>) : FormCheck

    /** Question id to the reason, in words, that it cannot be sent as it stands. */
    data class Problems(val byQuestion: Map<String, String>) : FormCheck
}

/**
 * An input request the agent is waiting on, as a form the person can fill in. Built only when every question is one this
 * app can ask: a request with a question of an unknown kind is left to be answered where it can be, rather than sent half
 * filled in.
 */
data class InputForm(val requestId: String, val message: String?, val questions: List<FormQuestion>) {
    /** The value a question starts with: the host's own default if it gave one. */
    fun initial(question: FormQuestion): FormValue = when (question.kind) {
        QuestionKind.TEXT, QuestionKind.NUMBER -> FormValue.Text(question.defaultText.orEmpty())
        QuestionKind.BOOLEAN -> FormValue.Bool(question.defaultBool ?: false)
        QuestionKind.SINGLE -> FormValue.Choice(null)
        QuestionKind.MULTI -> FormValue.Choices(emptySet())
    }

    /** Turns what was entered into the answers to send, or says in words what is wrong with each question that is not ready. */
    fun check(values: Map<String, FormValue>): FormCheck {
        val answers = LinkedHashMap<String, AnswerValue>()
        val problems = LinkedHashMap<String, String>()
        for (question in questions) {
            when (val result = answer(question, values[question.id] ?: initial(question))) {
                is Outcome.Answer -> answers[question.id] = result.value
                is Outcome.Skip -> Unit
                is Outcome.Problem -> problems[question.id] = result.reason
            }
        }
        return if (problems.isEmpty()) FormCheck.Ready(answers) else FormCheck.Problems(problems)
    }

    private sealed interface Outcome {
        data class Answer(val value: AnswerValue) : Outcome

        data object Skip : Outcome

        data class Problem(val reason: String) : Outcome
    }

    private fun answer(q: FormQuestion, value: FormValue): Outcome = when (q.kind) {
        QuestionKind.TEXT -> text(q, (value as? FormValue.Text)?.text.orEmpty())
        QuestionKind.NUMBER -> number(q, (value as? FormValue.Text)?.text.orEmpty())
        QuestionKind.BOOLEAN -> Outcome.Answer(AnswerValue.Bool((value as? FormValue.Bool)?.value ?: false))
        QuestionKind.SINGLE -> single(q, value as? FormValue.Choice ?: FormValue.Choice(null))
        QuestionKind.MULTI -> many(q, value as? FormValue.Choices ?: FormValue.Choices(emptySet()))
    }

    private fun text(q: FormQuestion, text: String): Outcome {
        if (text.isBlank()) return if (q.required) Outcome.Problem("Enter an answer.") else Outcome.Skip
        q.min?.takeIf { text.length < it }?.let { return Outcome.Problem("Enter at least ${it.toLong()} characters.") }
        q.max?.takeIf { text.length > it }?.let { return Outcome.Problem("Enter at most ${it.toLong()} characters.") }
        return Outcome.Answer(AnswerValue.Text(text))
    }

    private fun number(q: FormQuestion, text: String): Outcome {
        if (text.isBlank()) return if (q.required) Outcome.Problem("Enter a number.") else Outcome.Skip
        val parsed = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return Outcome.Problem("Enter a number.")
        q.min?.takeIf { parsed < it }?.let { return Outcome.Problem("Enter ${trim(it)} or more.") }
        q.max?.takeIf { parsed > it }?.let { return Outcome.Problem("Enter ${trim(it)} or less.") }
        return Outcome.Answer(AnswerValue.Number(parsed))
    }

    private fun single(q: FormQuestion, value: FormValue.Choice): Outcome {
        val typed = value.freeform.takeIf { q.allowFreeform && it.isNotBlank() }
        val picked = value.optionId?.takeIf { id -> q.options.any { it.id == id } }
        return when {
            picked != null -> Outcome.Answer(AnswerValue.Selected(picked, freeform = listOfNotNull(typed)))
            typed != null -> Outcome.Answer(AnswerValue.Selected(typed))
            q.required -> Outcome.Problem("Pick one.")
            else -> Outcome.Skip
        }
    }

    private fun many(q: FormQuestion, value: FormValue.Choices): Outcome {
        val picked = q.options.map { it.id }.filter { it in value.optionIds }
        val typed = value.freeform.takeIf { q.allowFreeform && it.isNotBlank() }
        val count = picked.size + if (typed != null) 1 else 0
        if (count == 0 && !q.required && (q.min ?: 0.0) <= 0.0) return Outcome.Skip
        if (count == 0 && q.required) return Outcome.Problem("Pick at least one.")
        q.min?.takeIf { count < it }?.let { return Outcome.Problem("Pick at least ${it.toLong()}.") }
        q.max?.takeIf { count > it }?.let { return Outcome.Problem("Pick at most ${it.toLong()}.") }
        return Outcome.Answer(AnswerValue.SelectedMany(picked, freeform = listOfNotNull(typed)))
    }

    private fun trim(number: Double): String = formatNumber(number)

    companion object {
        /** Null when there are no questions, or any is of a kind this app does not know. */
        fun from(request: ChatInputRequest): InputForm? {
            val questions = request.questions.orEmpty().ifEmpty { return null }.map { question(it) ?: return null }
            return InputForm(request.id, request.message?.takeIf { it.isNotBlank() }, questions)
        }

        private fun question(q: com.microsoft.agenthostprotocol.generated.ChatInputQuestion): FormQuestion? = when (q) {
            is ChatInputQuestionText -> q.value.run {
                FormQuestion(id, label(title, message), QuestionKind.TEXT, required != false, min = min?.toDouble(), max = max?.toDouble(), defaultText = defaultValue)
            }
            is ChatInputQuestionNumber -> q.value.run {
                FormQuestion(id, label(title, message), QuestionKind.NUMBER, required != false, min = min, max = max, defaultText = defaultValue?.let(::formatNumber))
            }
            is ChatInputQuestionBoolean -> q.value.run {
                FormQuestion(id, label(title, message), QuestionKind.BOOLEAN, required != false, defaultBool = defaultValue)
            }
            is ChatInputQuestionSingleSelect -> q.value.run {
                FormQuestion(id, label(title, message), QuestionKind.SINGLE, required != false, options.orEmpty().map(::option), allowFreeformInput == true)
            }
            is ChatInputQuestionMultiSelect -> q.value.run {
                FormQuestion(
                    id, label(title, message), QuestionKind.MULTI, required != false, options.orEmpty().map(::option), allowFreeformInput == true,
                    min = min?.toDouble(), max = max?.toDouble(),
                )
            }
            else -> null
        }

        private fun option(o: ChatInputOption) = FormOption(o.id, o.label.ifBlank { o.id })

        private fun label(title: String?, message: String?): String = message?.takeIf { it.isNotBlank() } ?: title.orEmpty()
    }
}

/** 3.0 as "3", 2.5 as "2.5". */
private fun formatNumber(number: Double): String = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
