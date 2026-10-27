package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatInputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputFormTest {
    private fun request(vararg questions: String, message: String? = null): ChatInputRequest = Ahp.json.decodeFromString(
        ChatInputRequest.serializer(),
        """{"id":"req-1"${message?.let { ""","message":"$it"""" }.orEmpty()},"questions":[${questions.joinToString(",")}]}""",
    )

    private fun q(kind: String, id: String, extra: String = "") = """{"kind":"$kind","id":"$id","title":"Title $id","message":"Message $id"$extra}"""

    private fun form(vararg questions: FormQuestion) = InputForm("req-1", null, questions.toList())

    private fun ready(check: FormCheck) = assertIs<FormCheck.Ready>(check).answers

    private fun problems(check: FormCheck) = assertIs<FormCheck.Problems>(check).byQuestion

    @Test
    fun `every known question kind becomes a field, in order, with the host's wording and bounds`() {
        val built = InputForm.from(
            request(
                q("text", "t", ""","min":2,"max":9,"defaultValue":"hi""""),
                q("number", "n", ""","min":1.5,"max":10,"defaultValue":3"""),
                q("boolean", "b", ""","defaultValue":true"""),
                q("single-select", "s", ""","options":[{"id":"a","label":"Alpha"},{"id":"b","label":""}],"allowFreeformInput":true"""),
                q("multi-select", "m", ""","options":[{"id":"x","label":"X"}],"min":1,"max":2"""),
                message = "Pick things",
            ),
        )!!

        assertEquals("req-1", built.requestId)
        assertEquals("Pick things", built.message)
        assertEquals(listOf("t", "n", "b", "s", "m"), built.questions.map { it.id })
        assertEquals(listOf(QuestionKind.TEXT, QuestionKind.NUMBER, QuestionKind.BOOLEAN, QuestionKind.SINGLE, QuestionKind.MULTI), built.questions.map { it.kind })
        assertEquals("Message t", built.questions[0].title)
        assertEquals(2.0, built.questions[0].min)
        assertEquals("hi", built.questions[0].defaultText)
        assertEquals("3", built.questions[1].defaultText, "a whole number reads as 3, not 3.0")
        assertEquals(true, built.questions[2].defaultBool)
        assertEquals(listOf(FormOption("a", "Alpha"), FormOption("b", "b")), built.questions[3].options, "a blank label falls back to the id")
        assertTrue(built.questions[3].allowFreeform)
        assertEquals(1.0, built.questions[4].min)
        assertEquals(2.0, built.questions[4].max)
    }

    @Test
    fun `a request with no questions, or any question this app does not know, is not a form`() {
        assertNull(InputForm.from(request()))
        assertNull(InputForm.from(request(q("text", "t"), q("hologram", "h"))))
    }

    @Test
    fun `a question that does not say it is optional is required`() {
        val built = InputForm.from(request(q("text", "a"), q("text", "b", ""","required":false"""), q("text", "c", ""","required":true""")))!!

        assertEquals(listOf(true, false, true), built.questions.map { it.required })
    }

    @Test
    fun `text needs an answer when required, may be left out when not, and keeps the bounds`() {
        val required = form(FormQuestion("t", "T", QuestionKind.TEXT, required = true, min = 3.0, max = 5.0))
        val optional = form(FormQuestion("t", "T", QuestionKind.TEXT, required = false))

        assertEquals("Enter an answer.", problems(required.check(mapOf("t" to FormValue.Text("  "))))["t"])
        assertEquals("Enter at least 3 characters.", problems(required.check(mapOf("t" to FormValue.Text("ab"))))["t"])
        assertEquals("Enter at most 5 characters.", problems(required.check(mapOf("t" to FormValue.Text("abcdef"))))["t"])
        assertEquals(mapOf("t" to AnswerValue.Text("abcd")), ready(required.check(mapOf("t" to FormValue.Text("abcd")))))
        assertEquals(emptyMap(), ready(optional.check(mapOf("t" to FormValue.Text("")))), "an optional question left blank is left out")
    }

    @Test
    fun `a number must be a finite number inside its bounds`() {
        val f = form(FormQuestion("n", "N", QuestionKind.NUMBER, required = true, min = 1.0, max = 10.0))

        listOf("", "abc", "NaN", "Infinity", "1e999").forEach { assertEquals("Enter a number.", problems(f.check(mapOf("n" to FormValue.Text(it))))["n"], it) }
        assertEquals("Enter 1 or more.", problems(f.check(mapOf("n" to FormValue.Text("0.5"))))["n"])
        assertEquals("Enter 10 or less.", problems(f.check(mapOf("n" to FormValue.Text("11"))))["n"])
        assertEquals(mapOf("n" to AnswerValue.Number(2.5)), ready(f.check(mapOf("n" to FormValue.Text(" 2.5 ")))))
    }

    @Test
    fun `a yes or no always has an answer, from the host's default or no`() {
        val f = form(FormQuestion("b", "B", QuestionKind.BOOLEAN, required = true, defaultBool = true), FormQuestion("c", "C", QuestionKind.BOOLEAN, required = true))

        assertEquals(mapOf("b" to AnswerValue.Bool(true), "c" to AnswerValue.Bool(false)), ready(f.check(emptyMap())))
        assertEquals(mapOf("b" to AnswerValue.Bool(false), "c" to AnswerValue.Bool(false)), ready(f.check(mapOf("b" to FormValue.Bool(false)))))
    }

    @Test
    fun `pick one needs a real option, or typed text when allowed`() {
        val strict = form(FormQuestion("s", "S", QuestionKind.SINGLE, required = true, options = listOf(FormOption("a", "A"), FormOption("b", "B"))))
        val open = form(FormQuestion("s", "S", QuestionKind.SINGLE, required = true, options = listOf(FormOption("a", "A")), allowFreeform = true))

        assertEquals("Pick one.", problems(strict.check(emptyMap()))["s"])
        assertEquals("Pick one.", problems(strict.check(mapOf("s" to FormValue.Choice("made-up"))))["s"], "an id the host did not offer is not an answer")
        assertEquals("Pick one.", problems(strict.check(mapOf("s" to FormValue.Choice(null, "typed"))))["s"], "typed text is ignored when the question does not allow it")
        assertEquals(mapOf("s" to AnswerValue.Selected("b")), ready(strict.check(mapOf("s" to FormValue.Choice("b")))))
        assertEquals(mapOf("s" to AnswerValue.Selected("typed")), ready(open.check(mapOf("s" to FormValue.Choice(null, "typed")))))
        assertEquals(mapOf("s" to AnswerValue.Selected("a", listOf("and more"))), ready(open.check(mapOf("s" to FormValue.Choice("a", "and more")))))
    }

    @Test
    fun `pick any keeps the host's option order, ignores unknown ids, and counts typed text toward the bounds`() {
        val f = form(
            FormQuestion("m", "M", QuestionKind.MULTI, required = true, options = listOf(FormOption("x", "X"), FormOption("y", "Y"), FormOption("z", "Z")), allowFreeform = true, min = 2.0, max = 3.0),
        )

        assertEquals("Pick at least one.", problems(f.check(emptyMap()))["m"])
        assertEquals("Pick at least 2.", problems(f.check(mapOf("m" to FormValue.Choices(setOf("x")))))["m"])
        assertEquals("Pick at least 2.", problems(f.check(mapOf("m" to FormValue.Choices(setOf("nope", "x")))))["m"])
        assertEquals("Pick at most 3.", problems(f.check(mapOf("m" to FormValue.Choices(setOf("x", "y", "z"), "extra"))))["m"])
        assertEquals(
            mapOf("m" to AnswerValue.SelectedMany(listOf("x", "z"), listOf("extra"))),
            ready(f.check(mapOf("m" to FormValue.Choices(setOf("z", "x"), "extra")))).let { it },
        )
    }

    @Test
    fun `every problem is reported at once, and nothing is sent while any remains`() {
        val f = form(
            FormQuestion("t", "T", QuestionKind.TEXT, required = true),
            FormQuestion("n", "N", QuestionKind.NUMBER, required = true),
            FormQuestion("b", "B", QuestionKind.BOOLEAN, required = true),
        )

        assertEquals(setOf("t", "n"), problems(f.check(emptyMap())).keys)
    }

    @Test
    fun `a draft is whatever means something so far, looser than a finished answer`() {
        val text = FormQuestion("t", "T", QuestionKind.TEXT, true, min = 5.0)
        val number = FormQuestion("n", "N", QuestionKind.NUMBER, true, max = 3.0)
        val single = FormQuestion("s", "S", QuestionKind.SINGLE, true, listOf(FormOption("a", "A")), allowFreeform = true)
        val many = FormQuestion("m", "M", QuestionKind.MULTI, true, listOf(FormOption("x", "X"), FormOption("y", "Y")), min = 2.0)
        val f = form(text, number, single, many)

        assertNull(f.draftFor(text, FormValue.Text("  ")), "blank clears the draft")
        assertEquals(AnswerValue.Text("ab"), f.draftFor(text, FormValue.Text("ab")), "below the minimum is still a draft")
        assertNull(f.draftFor(number, FormValue.Text("abc")))
        listOf("NaN", "Infinity", "-Infinity", "1e999").forEach { assertNull(f.draftFor(number, FormValue.Text(it)), "$it is not a number to send") }
        assertEquals(AnswerValue.Number(9.0), f.draftFor(number, FormValue.Text("9")), "above the maximum is still a draft")
        assertNull(f.draftFor(single, FormValue.Choice(null)))
        assertNull(f.draftFor(single, FormValue.Choice("made-up")), "an option the host did not offer is nothing")
        assertEquals(AnswerValue.Selected("a"), f.draftFor(single, FormValue.Choice("a")))
        assertEquals(AnswerValue.Selected("typed"), f.draftFor(single, FormValue.Choice(null, "typed")))
        assertNull(f.draftFor(many, FormValue.Choices(emptySet())))
        assertEquals(AnswerValue.SelectedMany(listOf("x", "y")), f.draftFor(many, FormValue.Choices(setOf("y", "x"))), "host order, and one pick is a draft though two are needed")
    }

    @Test
    fun `the host's own drafts are the starting values, where they fit the question they belong to`() {
        val built = InputForm.from(
            Ahp.json.decodeFromString(
                ChatInputRequest.serializer(),
                """{"id":"req-1","questions":[
                    ${q("text", "a")},
                    ${q("single-select", "s", ""","options":[{"id":"sp","label":"Spring"},{"id":"au","label":"Autumn"}]""")},
                    ${q("number", "n")}
                  ],
                  "answers":{
                    "a":{"state":"draft","value":{"kind":"text","value":"typed in VS Code"}},
                    "s":{"state":"draft","value":{"kind":"selected","value":"au"}},
                    "n":{"state":"draft","value":{"kind":"text","value":"wrong kind for a number question"}},
                    "zz":{"state":"draft","value":{"kind":"text","value":"no such question"}}
                  }}""",
            ),
        )!!

        assertEquals(setOf("a", "s"), built.drafts.keys)
        assertEquals(FormValue.Text("typed in VS Code"), built.initial(built.questions[0]))
        assertEquals(FormValue.Choice("au"), built.initial(built.questions[1]))
        assertEquals(FormValue.Text(""), built.initial(built.questions[2]), "no usable draft, so the default")
    }

    @Test
    fun `the starting value is the host's default`() {
        val f = form(
            FormQuestion("t", "T", QuestionKind.TEXT, true, defaultText = "hello"),
            FormQuestion("b", "B", QuestionKind.BOOLEAN, true, defaultBool = true),
            FormQuestion("s", "S", QuestionKind.SINGLE, true),
            FormQuestion("m", "M", QuestionKind.MULTI, true),
        )

        assertEquals(
            listOf(FormValue.Text("hello"), FormValue.Bool(true), FormValue.Choice(null), FormValue.Choices(emptySet())),
            f.questions.map { f.initial(it) },
        )
    }
}

class InteractMessagesTest {
    @Test
    fun `a message that went through says nothing`() {
        assertNull(InteractMessages.describe(InteractResult.Sent(DispatchOutcome.Accepted)))
        assertNull(InteractMessages.describe(InteractResult.Blank))
        assertTrue(InteractMessages.succeeded(InteractResult.Sent(DispatchOutcome.Accepted)))
    }

    @Test
    fun `nothing but an accepted echo counts as success`() {
        listOf(
            InteractResult.Sent(DispatchOutcome.NoReply),
            InteractResult.Sent(DispatchOutcome.Interrupted),
            InteractResult.Sent(DispatchOutcome.Rejected("no")),
            InteractResult.NotAllowed,
            InteractResult.NotConfirmed,
            InteractResult.NotConnected,
            InteractResult.NotSent(java.io.IOException()),
        ).forEach { assertEquals(false, InteractMessages.succeeded(it), "$it") }
    }

    @Test
    fun `a message that may or may not have gone through warns against sending it again`() {
        assertTrue(InteractMessages.describe(InteractResult.Sent(DispatchOutcome.NoReply))!!.contains("before sending it again"))
        assertTrue(InteractMessages.describe(InteractResult.Sent(DispatchOutcome.Interrupted))!!.contains("before sending it again"))
    }

    @Test
    fun `the host's own words are shown, but cut to a bounded length`() {
        val long = "x".repeat(5_000)

        val text = InteractMessages.describe(InteractResult.Sent(DispatchOutcome.Rejected(long)))!!

        assertTrue(text.startsWith("The host refused it: xxx"))
        assertTrue(text.length < 260, "${text.length}")
    }

    @Test
    fun `every other result has words of its own`() {
        listOf(InteractResult.NotAllowed, InteractResult.NoActiveTurn, InteractResult.NoSuchRequest, InteractResult.NotConfirmed, InteractResult.NotSent(java.io.IOException()))
            .forEach { assertTrue(!InteractMessages.describe(it).isNullOrBlank(), "$it") }
    }

    @Test
    fun `a button pressed with no connection to be had tells the person to open the app, and never says it was done`() {
        assertEquals("Open the app to answer.", InteractMessages.describe(InteractResult.NotConnected))
        for (approved in listOf(true, false)) {
            assertEquals("Open the app to answer.", InteractMessages.describeDecision(approved, InteractResult.NotConnected))
        }
    }

    @Test
    fun `a decision the host took is plainly approved or denied`() {
        val done = InteractResult.Sent(DispatchOutcome.Accepted)

        assertEquals("Approved.", InteractMessages.describeDecision(approved = true, done))
        assertEquals("Denied.", InteractMessages.describeDecision(approved = false, done))
    }

    @Test
    fun `a decision that did not happen says why, and never says it was approved or denied`() {
        val refused = listOf(
            InteractResult.NotAllowed,
            InteractResult.NoSuchRequest,
            InteractResult.NotConfirmed,
            InteractResult.NotSent(java.io.IOException("offline")),
            InteractResult.Sent(DispatchOutcome.Rejected("no")),
            InteractResult.Sent(DispatchOutcome.NoReply),
            InteractResult.Sent(DispatchOutcome.Interrupted),
        )

        for (result in refused) {
            for (approved in listOf(true, false)) {
                val text = InteractMessages.describeDecision(approved, result)
                assertEquals(false, text == "Approved." || text == "Denied.", "$result said it was done")
                assertEquals(InteractMessages.describe(result), text)
            }
        }
    }

    @Test
    fun `a decision with nothing to say still says that nothing was sent`() {
        assertEquals("Nothing was sent.", InteractMessages.describeDecision(approved = true, InteractResult.Blank))
    }
}
