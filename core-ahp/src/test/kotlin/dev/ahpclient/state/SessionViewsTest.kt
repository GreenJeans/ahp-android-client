package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionStatus
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionListModelTest {
    private val sessions = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items
    private val now = Instant.parse("2026-10-26T10:30:00.000Z")

    private fun model(showArchived: Boolean = false, list: List<com.microsoft.agenthostprotocol.generated.SessionSummary> = SessionCatalog.sorted(sessions)) =
        SessionListModel.from(list, showArchived, now)

    @Test
    fun `archived sessions are hidden by default and counted`() {
        val m = model()

        assertEquals(5, m.rows.size)
        assertEquals(1, m.hiddenArchived)
        assertEquals(6, m.total)
        assertTrue(m.rows.none { it.title == "Session A" })
    }

    @Test
    fun `showing archived brings every session back`() {
        val m = model(showArchived = true)

        assertEquals(6, m.rows.size)
        assertEquals(0, m.hiddenArchived)
    }

    @Test
    fun `an archived session that needs input is never hidden`() {
        val archivedAndWaiting = sessions.map {
            if (it.title == "Session E (synthetic: input needed)") it.copy(status = it.status or com.microsoft.agenthostprotocol.generated.SessionStatus.IS_ARCHIVED) else it
        }

        val m = model(list = archivedAndWaiting)

        assertTrue(m.rows.any { it.state == ActivityState.NEEDS_INPUT && it.isArchived })
    }

    @Test
    fun `row order follows the order given`() {
        assertEquals(
            listOf("Session E (synthetic: input needed)", "Session F (synthetic: error)", "Session D", "Session C", "Session B"),
            model().rows.map { it.title },
        )
    }

    @Test
    fun `counts and summary reflect what is shown`() {
        val m = model()

        assertEquals(1, m.needsInput)
        assertEquals(1, m.inProgress)
        assertEquals(1, m.errors)
        assertEquals("1 needs you · 1 working · 1 error", m.summary)
    }

    @Test
    fun `summary pluralises and stays empty when nothing is happening`() {
        val idle = sessions.filter { it.title == "Session B" || it.title == "Session D" }

        assertEquals("", model(list = idle).summary)
        assertEquals("2 need you", SessionListModel(emptyList(), needsInput = 2, inProgress = 0, errors = 0, hiddenArchived = 0, total = 2).summary)
    }

    @Test
    fun `a blank title gets a placeholder and the project is the subtitle`() {
        val blank = sessions.first { it.title == "Session C" }.copy(title = "  ")

        val row = model(list = listOf(blank)).rows.single()

        assertEquals(SessionListModel.UNTITLED, row.title)
        assertEquals("repo-c", row.subtitle)
    }

    @Test
    fun `activity wins over the project as the subtitle`() {
        val working = sessions.first { it.title == "Session C" }.copy(activity = "Running tests")

        assertEquals("Running tests", model(list = listOf(working)).rows.single().subtitle)
    }

    @Test
    fun `an empty host gives an empty model`() {
        val m = model(list = emptyList())

        assertEquals(0, m.rows.size)
        assertEquals("", m.summary)
    }

    private fun search(query: String, showArchived: Boolean = false) =
        SessionListModel.from(SessionCatalog.sorted(sessions), showArchived, now, query)

    @Test
    fun `a search keeps the sessions whose title or second line contains it, ignoring case`() {
        assertEquals(listOf("Session D"), search("session d").rows.map { it.title })
        assertEquals(listOf("Session C"), search("REPO-C").rows.map { it.title })
    }

    @Test
    fun `a blank search changes nothing, and the query is kept trimmed`() {
        assertEquals(model().rows, search("   ").rows)
        assertEquals("", search("   ").query)
        assertEquals("repo-c", search("  repo-c ").query)
    }

    @Test
    fun `a search leaves the counts and the summary as the host sees them`() {
        val m = search("Session B")

        assertEquals(1, m.rows.size)
        assertEquals(model().summary, m.summary)
        assertEquals(1, m.needsInput)
        assertEquals(6, m.total)
    }

    @Test
    fun `a search that matches nothing is an empty list that still knows how many sessions there are`() {
        val m = search("no such session")

        assertEquals(0, m.rows.size)
        assertEquals(6, m.total)
        assertEquals("no such session", m.query)
    }

    @Test
    fun `a search only looks inside what the archive filter lets through`() {
        assertTrue(search("Session A").rows.isEmpty())
        assertEquals(1, search("Session A", showArchived = true).rows.size)
    }
}

class RelativeTimeTest {
    private val now = Instant.parse("2026-10-26T15:00:00.000Z")
    private val utc = ZoneId.of("UTC")

    private fun at(iso: String) = RelativeTime.format(iso, now, utc, Locale.US)

    @Test
    fun `under a minute is now, and so is a timestamp slightly in the future`() {
        assertEquals("now", at("2026-10-26T14:59:30.000Z"))
        assertEquals("now", at("2026-10-26T15:00:20.000Z"))
    }

    @Test
    fun `minutes and hours`() {
        assertEquals("1m", at("2026-10-26T14:59:00.000Z"))
        assertEquals("59m", at("2026-10-26T14:01:00.000Z"))
        assertEquals("1h", at("2026-10-26T14:00:00.000Z"))
        assertEquals("23h", at("2026-10-25T16:00:00.000Z"))
    }

    @Test
    fun `a day ago is calendar-based, so 20 hours ago across midnight is 1d`() {
        assertEquals("1d", at("2026-10-25T09:00:00.000Z"))
    }

    @Test
    fun `days within the week, then a date`() {
        assertEquals("3d", at("2026-10-23T09:00:00.000Z"))
        assertEquals("Oct 10, 2026", at("2026-10-10T09:00:00.000Z"))
    }

    @Test
    fun `an unreadable timestamp is empty rather than an error`() {
        assertEquals("", at("not a date"))
        assertEquals("", at(""))
    }
}

class SessionDetailTest {
    private val now = Instant.parse("2026-10-26T10:30:00.000Z")

    private fun state(inputNeeded: String? = null, extra: String = ""): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"Fix the build","status":56,"lifecycle":"ready","activeClients":[],
            "project":{"uri":"file:///work/repo","displayName":"repo"},"workingDirectories":["file:///work/repo"],
            "chats":[{"resource":"ahp-chat://default/x","title":"Main","status":56,"modifiedAt":"2026-10-26T10:29:00.000Z"}]
            ${if (inputNeeded != null) ""","inputNeeded":$inputNeeded""" else ""}$extra}""",
    )

    @Test
    fun `a session decodes into the fields the detail screen shows`() {
        val d = SessionDetail.from(state(), now)

        assertEquals("Fix the build", d.title)
        assertEquals(ActivityState.NEEDS_INPUT, d.state)
        assertEquals("copilotcli", d.provider)
        assertEquals("repo", d.project)
        assertEquals(listOf("file:///work/repo").map(::displayPath), d.workingDirectories)
        assertEquals(listOf(ChatRow("ahp-chat://default/x", "Main", ActivityState.NEEDS_INPUT, null, "1m", removable = false)), d.chats, "a lone chat is the main one")
        assertEquals(emptyList(), d.inputNeeded)
    }

    @Test
    fun `a chat question shows its message`() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"ahp-chat://default/x","request":{"id":"q","message":"Which branch should I use?"}}]"""

        assertEquals(
            listOf(InputRequestView("Question", "Which branch should I use?")),
            SessionDetail.from(state(request), now).inputNeeded,
        )
    }

    @Test
    fun `a chat question without a message shows its first question`() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","questions":[
            {"kind":"single-select","id":"a","title":"Branch","message":"Pick a branch","options":[{"id":"m","label":"main"}]}]}}]"""

        assertEquals("Pick a branch", SessionDetail.from(state(request), now).inputNeeded.single().text)
    }

    @Test
    fun `a chat question with nothing readable still says something`() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q"}}]"""

        assertEquals("The agent has a question.", SessionDetail.from(state(request), now).inputNeeded.single().text)
    }

    @Test
    fun `a tool approval names the tool and the intention`() {
        val request = """[{"kind":"toolConfirmation","id":"r2","chat":"c","turnId":"t","toolCall":{"status":"pending-confirmation",
            "toolCallId":"tc","toolName":"run_in_terminal","displayName":"Run command","intention":"Run the tests",
            "invocationMessage":"Run npm test"}}]"""

        assertEquals(
            listOf(InputRequestView("Approval", "Run command: Run the tests")),
            SessionDetail.from(state(request), now).inputNeeded,
        )
    }

    @Test
    fun `a tool approval falls back to the invocation message, then to the bare name`() {
        val withMessage = """[{"kind":"toolConfirmation","id":"r2","chat":"c","turnId":"t","toolCall":{"status":"pending-confirmation",
            "toolCallId":"tc","toolName":"run_in_terminal","displayName":"Run command","invocationMessage":"Run npm test"}}]"""
        val bare = """[{"kind":"toolConfirmation","id":"r2","chat":"c","turnId":"t","toolCall":{"status":"pending-confirmation",
            "toolCallId":"tc","toolName":"run_in_terminal","displayName":"","invocationMessage":""}}]"""

        assertEquals("Run command: Run npm test", SessionDetail.from(state(withMessage), now).inputNeeded.single().text)
        assertEquals("run_in_terminal", SessionDetail.from(state(bare), now).inputNeeded.single().text)
    }

    @Test
    fun `a file uri is shown as the path it names`() {
        assertEquals("c:/Users/me/source/repos/app", displayPath("file:///c%3A/Users/me/source/repos/app"))
        assertEquals("C:/My Projects/app", displayPath("file:///C:/My%20Projects/app"))
        assertEquals("/home/me/app", displayPath("file:///home/me/app"))
    }

    @Test
    fun `anything that is not a file uri is left alone, and a malformed one does not throw`() {
        assertEquals("vscode-remote://ssh-remote+box/home/me", displayPath("vscode-remote://ssh-remote+box/home/me"))
        assertEquals("not a uri at all", displayPath("not a uri at all"))
        assertEquals("", displayPath(""))
    }

    @Test
    fun `working directories on the detail are shown as paths`() {
        val d = SessionDetail.from(
            Ahp.json.decodeFromString(
                SessionState.serializer(),
                """{"provider":"p","title":"t","status":1,"lifecycle":"ready","activeClients":[],"chats":[],"workingDirectories":["file:///c%3A/work/repo"]}""",
            ),
            now,
        )

        assertEquals(listOf("c:/work/repo"), d.workingDirectories)
    }

    @Test
    fun `a pick-one question lists its options, with description and the recommended one marked`() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","questions":[
            {"kind":"single-select","id":"a","message":"Which branch?","options":[
                {"id":"m","label":"main","description":"the default","recommended":true},{"id":"d","label":"develop"}]}]}}]"""

        val view = SessionDetail.from(state(request), now).inputNeeded.single()

        assertEquals("Which branch?", view.text)
        assertEquals(
            listOf(QuestionView(prompt = null, answerKind = "Pick one", options = listOf("main — the default (recommended)", "develop"))),
            view.questions,
        )
    }

    @Test
    fun `a message headline keeps every question below it, each with its own prompt`() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","message":"A few things","questions":[
            {"kind":"multi-select","id":"a","message":"Which files?","options":[{"id":"1","label":"a.ts"},{"id":"2","label":"b.ts"}]},
            {"kind":"boolean","id":"b","message":"Run the tests?"},
            {"kind":"text","id":"c","title":"Notes","message":""},
            {"kind":"number","id":"d","message":"How many?"}]}}]"""

        val view = SessionDetail.from(state(request), now).inputNeeded.single()

        assertEquals("A few things", view.text)
        assertEquals(
            listOf(
                QuestionView("Which files?", "Pick any", listOf("a.ts", "b.ts")),
                QuestionView("Run the tests?", "Yes or no", emptyList()),
                QuestionView("Notes", "Free text", emptyList()),
                QuestionView("How many?", "Number", emptyList()),
            ),
            view.questions,
        )
    }

    @Test
    fun `an option with no label falls back to its id`() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","questions":[
            {"kind":"single-select","id":"a","message":"Pick","options":[{"id":"opt-1","label":""}]}]}}]"""

        assertEquals(listOf("opt-1"), SessionDetail.from(state(request), now).inputNeeded.single().questions.single().options)
    }

    @Test
    fun `a question kind this client does not know is still listed`() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","message":"Hmm","questions":[{"kind":"hologram","id":"a"}]}}]"""

        assertEquals(
            listOf(QuestionView("A question this app cannot show.", "Unknown", emptyList())),
            SessionDetail.from(state(request), now).inputNeeded.single().questions,
        )
    }

    @Test
    fun `a request with only a message has no question rows`() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","message":"Ready?"}}]"""

        assertEquals(emptyList(), SessionDetail.from(state(request), now).inputNeeded.single().questions)
    }

    @Test
    fun `a tool the client is asked to run shows its name`() {
        val request = """[{"kind":"toolClientExecution","id":"r3","chat":"c","turnId":"t","clientId":"cl","toolCall":{"status":"running",
            "toolCallId":"tc","toolName":"read_file","displayName":"Read file","invocationMessage":"Reading","confirmed":"not-needed"}}]"""

        assertEquals(listOf(InputRequestView("Tool run", "Read file")), SessionDetail.from(state(request), now).inputNeeded)
    }

    @Test
    fun `a tool that needs sign-in shows its name, falling back to the tool id`() {
        val auth = """{"reason":"required","resource":{"resource":"https://mcp.example"}}"""
        val named = """[{"kind":"toolAuthentication","id":"r4","chat":"c","turnId":"t","toolCall":{"status":"auth-required",
            "toolCallId":"tc","toolName":"mcp_search","displayName":"Search","invocationMessage":"Searching","confirmed":"not-needed","auth":$auth}}]"""
        val unnamed = named.replace(""""displayName":"Search"""", """"displayName":""""")

        assertEquals(listOf(InputRequestView("Sign-in", "Search")), SessionDetail.from(state(named), now).inputNeeded)
        assertEquals(listOf(InputRequestView("Sign-in", "mcp_search")), SessionDetail.from(state(unnamed), now).inputNeeded)
    }

    @Test
    fun `approving a result shows the tool and its markdown message`() {
        val request = """[{"kind":"toolConfirmation","id":"r5","chat":"c","turnId":"t","toolCall":{"status":"pending-result-confirmation",
            "toolCallId":"tc","toolName":"run_in_terminal","displayName":"Run command","invocationMessage":{"markdown":"Ran **npm test**"},
            "pastTenseMessage":"Ran it","confirmed":"not-needed","success":true}}]"""

        assertEquals(listOf(InputRequestView("Approval", "Run command: Ran npm test")), SessionDetail.from(state(request), now).inputNeeded)
    }

    @Test
    fun `a tool confirmation of a kind this client does not know still says a tool needs approval`() {
        val request = """[{"kind":"toolConfirmation","id":"r6","chat":"c","turnId":"t","toolCall":{"status":"hologram","toolCallId":"tc"}}]"""

        assertEquals(listOf(InputRequestView("Approval", "A tool needs approval.")), SessionDetail.from(state(request), now).inputNeeded)
    }

    @Test
    fun `the changesets a session advertises are listed, and only plain ones can be opened`() {
        val detail = SessionDetail.from(
            state(extra = ""","changesets":[{"label":"Uncommitted","uriTemplate":"ahp-changeset:/c1","changeKind":"uncommitted"},{"label":"Turn","uriTemplate":"ahp-changeset:/t/{turnId}","changeKind":"turn"}]"""),
            now,
        )

        assertEquals(listOf("Uncommitted", "Turn"), detail.changesets.map { it.label })
        assertEquals(listOf("ahp-changeset:/c1", null), detail.changesets.map { it.uri })
        assertEquals(emptyList(), SessionDetail.from(state(), now).changesets)
    }

    @Test
    fun `read and archived come from the status flags, and a plain session is neither`() {
        val read = state().copy(status = SessionStatus.IDLE or SessionStatus.IS_READ)
        val archived = state().copy(status = SessionStatus.IDLE or SessionStatus.IS_ARCHIVED)
        val plain = state().copy(status = SessionStatus.IDLE)

        assertEquals(listOf(true, false), SessionDetail.from(read, now).let { listOf(it.isRead, it.isArchived) })
        assertEquals(listOf(false, true), SessionDetail.from(archived, now).let { listOf(it.isRead, it.isArchived) })
        assertEquals(listOf(false, false), SessionDetail.from(plain, now).let { listOf(it.isRead, it.isArchived) })
    }

    @Test
    fun `lifecycle words map to phases, and a word this client does not know is never taken as ready`() {
        assertEquals(SessionPhase.CREATING, SessionPhase.of("creating"))
        assertEquals(SessionPhase.READY, SessionPhase.of("ready"))
        assertEquals(SessionPhase.FAILED, SessionPhase.of("failed"))
        assertEquals(SessionPhase.OTHER, SessionPhase.of("hibernating"))
        assertEquals(SessionPhase.OTHER, SessionPhase.of(""))
        assertEquals(SessionPhase.READY, SessionDetail.from(state(), now).phase)
    }

    @Test
    fun `a session that is still starting or has failed says so, with the host's reason clipped`() {
        val creating = Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"copilotcli","title":"New","status":1,"lifecycle":"creating","activeClients":[],"chats":[]}""",
        )
        val failed = Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"copilotcli","title":"New","status":1,"lifecycle":"failed","activeClients":[],"chats":[],
                "creationError":{"errorType":"E","message":"${"x".repeat(5_000)}"}}""",
        )

        val a = SessionDetail.from(creating, now)
        val b = SessionDetail.from(failed, now)

        assertEquals(SessionPhase.CREATING, a.phase)
        assertNull(a.creationError)
        assertEquals(SessionPhase.FAILED, b.phase)
        assertEquals(200, b.creationError!!.length)
    }

    @Test
    fun `the state labels are the words the screens show`() {
        assertEquals(
            listOf("Needs you", "Working", "Error", "Idle"),
            listOf(ActivityState.NEEDS_INPUT, ActivityState.IN_PROGRESS, ActivityState.ERROR, ActivityState.IDLE).map { it.label },
        )
    }

    @Test
    fun `a request kind this client does not know is still listed`() {
        val request = """[{"kind":"somethingNew","id":"r9","payload":1}]"""

        assertEquals(
            listOf(InputRequestView("Input", "The agent is waiting for input.")),
            SessionDetail.from(state(request), now).inputNeeded,
        )
    }

    @Test
    fun `several requests keep their order`() {
        val request = """[{"kind":"chatInput","id":"a","chat":"c","request":{"id":"q","message":"first"}},
            {"kind":"chatInput","id":"b","chat":"c","request":{"id":"q2","message":"second"}}]"""

        assertEquals(listOf("first", "second"), SessionDetail.from(state(request), now).inputNeeded.map { it.text })
    }

    @Test
    fun `the answer-elsewhere hint stays a constant the UI can show`() {
        assertEquals("Answer in VS Code.", SessionDetail.ANSWER_ELSEWHERE)
    }
}
