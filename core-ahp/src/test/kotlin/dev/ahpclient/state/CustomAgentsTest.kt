package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomAgentsTest {
    private fun agent(name: String, uri: String, extra: String = "") =
        """{"type":"agent","id":"i-$name","uri":"$uri","name":"$name"$extra}"""

    private fun plugin(children: String, enablement: String? = null) =
        """{"type":"plugin","id":"p1","uri":"file:///plugins/p1","name":"P1","children":[$children]${enablement?.let { ""","enablement":$it""" }.orEmpty()}}"""

    private fun directory(children: String, enabled: Boolean = true) =
        """{"type":"directory","id":"d1","uri":"file:///work/.github/agents","name":"agents","contents":"agent","writable":true,"enabled":$enabled,"children":[$children]}"""

    private fun session(customizations: String?): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"T","status":1,"lifecycle":"ready","activeClients":[],"chats":[]${customizations?.let { ""","customizations":[$it]""" }.orEmpty()}}""",
    )

    private fun chat(turns: String = "", active: String? = null, draft: String? = null): ChatState = Ahp.json.decodeFromString(
        ChatState.serializer(),
        """{"resource":"ahp-chat:/c","title":"Chat","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z","turns":[$turns]
            ${draft?.let { ""","draft":$it""" }.orEmpty()}${active?.let { ""","activeTurn":$it""" }.orEmpty()}}""",
    )

    private fun turn(id: String, agent: String?) =
        """{"id":"$id","message":{"text":"x","origin":{"kind":"user"}${agent?.let { ""","agent":{"uri":"$it"}""" }.orEmpty()}},"responseParts":[],"startedAt":"2026-10-22T10:00:00.000Z","state":"complete"}"""

    // ---- which agents are offered ---------------------------------------------------------------------------------

    @Test
    fun `agents inside plugins and folders are offered, in the order the host lists them`() {
        val s = session(plugin(agent("plan", "file:///a/plan.agent.md", ""","description":"Plan first"""")) + "," + directory(agent("review", "file:///a/review.agent.md")))

        assertEquals(
            listOf(AgentChoice("file:///a/plan.agent.md", "plan", "Plan first"), AgentChoice("file:///a/review.agent.md", "review", null)),
            CustomAgents.of(s),
        )
    }

    @Test
    fun `nothing is offered when the session lists no customizations or none that are agents`() {
        assertEquals(emptyList(), CustomAgents.of(null))
        assertEquals(emptyList(), CustomAgents.of(session(null)))
        assertEquals(emptyList(), CustomAgents.of(session(plugin("""{"type":"skill","id":"s","uri":"file:///s","name":"s"}"""))))
        assertEquals(emptyList(), CustomAgents.of(session("""{"type":"mcpServer","id":"m","uri":"file:///m","name":"m","state":{"status":"stopped"}}""")))
    }

    @Test
    fun `an agent that is turned off, or reserved for the agent's own delegation, is not offered`() {
        val s = session(
            plugin(
                agent("off", "file:///a/off.agent.md", ""","enabled":false""") + "," +
                    agent("reserved", "file:///a/reserved.agent.md", ""","disableUserInvocation":true""") + "," +
                    agent("ok", "file:///a/ok.agent.md", ""","enabled":true,"disableUserInvocation":false"""),
            ),
        )

        assertEquals(listOf("ok"), CustomAgents.of(s).map { it.name })
    }

    @Test
    fun `a plugin turned off for the session, or a folder turned off, offers none of its agents`() {
        val offPlugin = plugin(agent("a", "file:///a.agent.md"), enablement = """[{"kind":"session","enabled":false},{"kind":"global","enabled":true}]""")
        val onPlugin = plugin(agent("b", "file:///b.agent.md"), enablement = """[{"kind":"session","enabled":true},{"kind":"global","enabled":false}]""")
        val offFolder = directory(agent("c", "file:///c.agent.md"), enabled = false)

        assertEquals(listOf("b"), CustomAgents.of(session("$offPlugin,$onPlugin,$offFolder")).map { it.name }, "the most specific decision, first in the list, is the one that counts")
    }

    @Test
    fun `an agent listed twice is offered once, and a blank name falls back to the file's`() {
        val s = session(plugin(agent("", "file:///a/plan.agent.md") + "," + agent("again", "file:///a/plan.agent.md")))

        assertEquals(listOf(AgentChoice("file:///a/plan.agent.md", "plan", null)), CustomAgents.of(s))
    }

    @Test
    fun `the host's words are cut to a sane length and the list is bounded`() {
        val long = "n".repeat(500)
        val many = (1..150).joinToString(",") { agent("a$it", "file:///a/$it.agent.md") }

        val first = CustomAgents.of(session(plugin(agent(long, "file:///l.agent.md", ""","description":"$long""""))))
        assertEquals(80, first.single().name.length)
        assertEquals(200, first.single().description!!.length)
        assertEquals(CustomAgents.MAX_AGENTS, CustomAgents.of(session(plugin(many))).size)
    }

    // ---- which agent a chat is on ---------------------------------------------------------------------------------

    @Test
    fun `a chat is on the agent its latest message was sent with`() {
        assertEquals("file:///b", CustomAgents.inUse(chat(turns = turn("t1", "file:///a") + "," + turn("t2", "file:///b"))))
    }

    @Test
    fun `a running turn counts before the turns that finished`() {
        val active = """{"id":"t3","message":{"text":"x","origin":{"kind":"user"},"agent":{"uri":"file:///c"}},"responseParts":[],"startedAt":"2026-10-22T10:05:00.000Z"}"""

        assertEquals("file:///c", CustomAgents.inUse(chat(turns = turn("t1", "file:///a"), active = active)))
    }

    @Test
    fun `a latest message that names no agent means no agent, not the one before it`() {
        assertNull(CustomAgents.inUse(chat(turns = turn("t1", "file:///a") + "," + turn("t2", null))))
    }

    @Test
    fun `a chat with no message yet is on the agent in its draft, if any`() {
        assertEquals("file:///d", CustomAgents.inUse(chat(draft = """{"text":"","origin":{"kind":"user"},"agent":{"uri":"file:///d"}}""")))
        assertNull(CustomAgents.inUse(chat()))
        assertNull(CustomAgents.inUse(null))
    }

    @Test
    fun `a draft does not outrank a message that was sent`() {
        val draft = """{"text":"","origin":{"kind":"user"},"agent":{"uri":"file:///d"}}"""

        assertNull(CustomAgents.inUse(chat(turns = turn("t1", null), draft = draft)))
    }

    // ---- naming -----------------------------------------------------------------------------------------------------

    @Test
    fun `an agent is called by the name it was offered with, else by its file`() {
        val offered = listOf(AgentChoice("file:///a/plan.agent.md", "Planner", null))

        assertEquals("Planner", CustomAgents.nameOf("file:///a/plan.agent.md", offered))
        assertEquals("review", CustomAgents.nameOf("file:///a/review.agent.md?x=1#frag", offered))
        assertEquals("notes", CustomAgents.nameOf("file:///a/notes.md", emptyList()))
        assertNull(CustomAgents.nameOf(null, offered))
        assertNull(CustomAgents.nameOf("  ", offered))
    }
}
