package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatInputAnswerDraft
import com.microsoft.agenthostprotocol.generated.ChatInputAnswerValue
import com.microsoft.agenthostprotocol.generated.ChatInputAnswerValueBoolean
import com.microsoft.agenthostprotocol.generated.ChatInputAnswerValueNumber
import com.microsoft.agenthostprotocol.generated.ChatInputAnswerValueSelected
import com.microsoft.agenthostprotocol.generated.ChatInputAnswerValueSelectedMany
import com.microsoft.agenthostprotocol.generated.ChatInputAnswerValueText
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
data class InputForm(
    val requestId: String,
    val message: String?,
    val questions: List<FormQuestion>,
    /** What the host already holds as in-progress answers (typed in VS Code, say). They are only the starting values: a form being filled in is never overwritten. */
    val drafts: Map<String, FormValue> = emptyMap(),
) {
    /** The value a question starts with: the host's draft if there is one, else its own default. */
    fun initial(question: FormQuestion): FormValue = drafts[question.id] ?: when (question.kind) {
        QuestionKind.TEXT, QuestionKind.NUMBER -> FormValue.Text(question.defaultText.orEmpty())
        QuestionKind.BOOLEAN -> FormValue.Bool(question.defaultBool ?: false)
        QuestionKind.SINGLE -> FormValue.Choice(null)
        QuestionKind.MULTI -> FormValue.Choices(emptySet())
    }

    /**
     * The in-progress answer to send for one question, or null to clear it. Looser than [check]: a draft is a work in
     * progress, so bounds and required-ness are not enforced; only a value that means something is sent.
     */
    fun draftFor(question: FormQuestion, value: FormValue): AnswerValue? = when (question.kind) {
        QuestionKind.TEXT -> (value as? FormValue.Text)?.text?.takeIf { it.isNotBlank() }?.let { AnswerValue.Text(it) }
        QuestionKind.NUMBER -> (value as? FormValue.Text)?.text?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { AnswerValue.Number(it) }
        QuestionKind.BOOLEAN -> (value as? FormValue.Bool)?.let { AnswerValue.Bool(it.value) }
        QuestionKind.SINGLE -> (value as? FormValue.Choice)?.let { choice ->
            val picked = choice.optionId?.takeIf { id -> question.options.any { it.id == id } }
            val typed = choice.freeform.takeIf { question.allowFreeform && it.isNotBlank() }
            when {
                picked != null -> AnswerValue.Selected(picked, listOfNotNull(typed))
                typed != null -> AnswerValue.Selected(typed)
                else -> null
            }
        }
        QuestionKind.MULTI -> (value as? FormValue.Choices)?.let { choices ->
            val picked = question.options.map { it.id }.filter { it in choices.optionIds }
            val typed = choices.freeform.takeIf { question.allowFreeform && it.isNotBlank() }
            if (picked.isEmpty() && typed == null) null else AnswerValue.SelectedMany(picked, listOfNotNull(typed))
        }
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
            return InputForm(request.id, request.message?.takeIf { it.isNotBlank() }, questions, draftsOf(request, questions))
        }

        /** What the host holds as in-progress answers, kept only where it fits the question it belongs to. */
        private fun draftsOf(request: ChatInputRequest, questions: List<FormQuestion>): Map<String, FormValue> {
            val held = request.answers ?: return emptyMap()
            val drafts = LinkedHashMap<String, FormValue>()
            for (question in questions) {
                val value = (held[question.id] as? ChatInputAnswerDraft)?.value?.value ?: continue
                formValueOf(question, value)?.let { drafts[question.id] = it }
            }
            return drafts
        }

        private fun formValueOf(question: FormQuestion, value: ChatInputAnswerValue): FormValue? = when (question.kind) {
            QuestionKind.TEXT -> (value as? ChatInputAnswerValueText)?.value?.value?.let { FormValue.Text(it) }
            QuestionKind.NUMBER -> (value as? ChatInputAnswerValueNumber)?.value?.value?.let { FormValue.Text(formatNumber(it)) }
            QuestionKind.BOOLEAN -> (value as? ChatInputAnswerValueBoolean)?.value?.value?.let { FormValue.Bool(it) }
            QuestionKind.SINGLE -> (value as? ChatInputAnswerValueSelected)?.value?.let { selected ->
                val known = question.options.any { it.id == selected.value }
                FormValue.Choice(selected.value.takeIf { known }, if (known) selected.freeformValues.orEmpty().firstOrNull().orEmpty() else selected.value)
            }
            QuestionKind.MULTI -> (value as? ChatInputAnswerValueSelectedMany)?.value?.let { many ->
                FormValue.Choices(many.value.filter { id -> question.options.any { it.id == id } }.toSet(), many.freeformValues.orEmpty().firstOrNull().orEmpty())
            }
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
