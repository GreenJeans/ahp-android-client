package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.RootState
import com.microsoft.agenthostprotocol.generated.SessionState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomizationsTest {
    private fun skill(name: String, uri: String, extra: String = "") =
        """{"type":"skill","id":"s-$name","uri":"$uri","name":"$name"$extra}"""

    private fun prompt(name: String, uri: String, extra: String = "") =
        """{"type":"prompt","id":"p-$name","uri":"$uri","name":"$name"$extra}"""

    private fun rule(name: String, uri: String, extra: String = "") =
        """{"type":"rule","id":"r-$name","uri":"$uri","name":"$name"$extra}"""

    private fun agent(name: String, uri: String, extra: String = "") =
        """{"type":"agent","id":"a-$name","uri":"$uri","name":"$name"$extra}"""

    private fun hook(name: String, uri: String, extra: String = "") =
        """{"type":"hook","id":"h-$name","uri":"$uri","name":"$name"$extra}"""

    private fun mcp(name: String, uri: String, status: String = "ready", extra: String = "") =
        """{"type":"mcpServer","id":"m-$name","uri":"$uri","name":"$name","state":{"kind":"$status"$extra}}"""

    private fun plugin(children: String, name: String = "P1", enablement: String? = null, version: String? = null, load: String? = null) =
        """{"type":"plugin","id":"p-$name","uri":"file:///plugins/$name","name":"$name","children":[$children]${enablement?.let { ""","enablement":$it""" }.orEmpty()}${version?.let { ""","version":"$it"""" }.orEmpty()}${load?.let { ""","load":$it""" }.orEmpty()}}"""

    private fun directory(children: String, name: String = "skills", enabled: Boolean = true) =
        """{"type":"directory","id":"d-$name","uri":"file:///work/.github/$name","name":"$name","contents":"skill","writable":true,"enabled":$enabled,"children":[$children]}"""

    private fun session(customizations: String?, serverTools: String? = null, provider: String = "copilotcli"): SessionState =
        Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"$provider","title":"T","status":1,"lifecycle":"ready","activeClients":[],"chats":[]${customizations?.let { ""","customizations":[$it]""" }.orEmpty()}${serverTools?.let { ""","serverTools":[$it]""" }.orEmpty()}}""",
        )

    private fun root(provider: String, customizations: String): RootState =
        Ahp.json.decodeFromString(
            RootState.serializer(),
            """{"agents":[{"provider":"$provider","displayName":"Copilot","description":"d","models":[],"customizations":[$customizations]}]}""",
        )

    // ---- argumentHint in HostExtras ---------------------------------------------------------------------------------

    @Test
    fun `argumentHint reads vscode and ahpd meta keys and collapses whitespace`() {
        assertEquals(
            "<pack-name> [signals]",
            HostExtras.argumentHint(mapOf("vscode.skill.argumentHint" to JsonPrimitive("  <pack-name>\n  [signals]  "))),
        )
        assertEquals(
            "[target]",
            HostExtras.argumentHint(mapOf("ahpd.argumentHint" to JsonPrimitive("[target]"))),
        )
        assertEquals(
            "--verbose",
            HostExtras.argumentHint(
                mapOf("vscode" to JsonObject(mapOf("skill" to JsonObject(mapOf("argumentHint" to JsonPrimitive("--verbose")))))),
            ),
        )
        assertNull(HostExtras.argumentHint(null))
        assertNull(HostExtras.argumentHint(emptyMap()))
    }

    // ---- SlashCommands extraction -----------------------------------------------------------------------------------

    @Test
    fun `skills and prompts from enabled plugins and folders become slash commands with argument hints`() {
        val s = session(
            plugin(
                skill(
                    "skill-eval",
                    "file:///skills/skill-eval/SKILL.md",
                    ""","description":"Score a skill pack against the quality rubric","_meta":{"vscode.skill.argumentHint":"[signals]"}""",
                ) + "," +
                    prompt(
                        "review-pr",
                        "file:///prompts/review-pr.prompt.md",
                        ""","description":"Review the active pull request"""",
                    ),
                name = "nexus-pack",
            ) + "," +
                directory(
                    skill("skill-kb", "file:///work/.github/skills/skill-kb/SKILL.md", ""","description":"Build a pack's KB""""),
                ),
        )

        val commands = SlashCommands.of(s, includeBuiltIns = false)
        assertEquals(3, commands.size)
        assertEquals(
            SlashCommand(
                name = "skill-eval",
                description = "Score a skill pack against the quality rubric",
                argumentHint = "[signals]",
                kind = SlashCommandKind.SKILL,
                uri = "file:///skills/skill-eval/SKILL.md",
                source = "nexus-pack",
            ),
            commands[0],
        )
        assertEquals(SlashCommandKind.PROMPT, commands[1].kind)
        assertEquals("review-pr", commands[1].name)
        assertEquals("skill-kb", commands[2].name)
    }

    @Test
    fun `disabled skills, model-only skills, and skills in disabled plugins or folders are excluded from slash commands`() {
        val s = session(
            plugin(
                skill("off", "file:///s/off/SKILL.md", ""","enabled":false""") + "," +
                    skill("model-only", "file:///s/mo/SKILL.md", ""","disableUserInvocation":true""") + "," +
                    skill("active", "file:///s/active/SKILL.md", ""","enabled":true,"disableUserInvocation":false"""),
            ) + "," +
                plugin(
                    skill("plugin-off", "file:///s/po/SKILL.md"),
                    name = "P2",
                    enablement = """[{"kind":"session","enabled":false}]""",
                ) + "," +
                directory(
                    skill("dir-off", "file:///s/do/SKILL.md"),
                    enabled = false,
                ),
        )

        val names = SlashCommands.of(s, includeBuiltIns = false).map { it.name }
        assertEquals(listOf("active"), names)
    }

    @Test
    fun `blank or SKILL dot md skill names fall back to the parent folder name and strip leading slashes`() {
        val s = session(
            plugin(
                skill("", "file:///work/skills/my-custom-skill/SKILL.md") + "," +
                    skill("SKILL.md", "file:///work/skills/another-skill/SKILL.md") + "," +
                    skill("/slash-prefixed", "file:///work/skills/slash-prefixed/SKILL.md") + "," +
                    prompt("", "file:///work/prompts/quick-fix.prompt.md"),
            ),
        )

        val names = SlashCommands.of(s, includeBuiltIns = false).map { it.name }
        assertEquals(listOf("my-custom-skill", "another-skill", "slash-prefixed", "quick-fix"), names)
    }

    @Test
    fun `provider customizations in RootState supplement session customizations without duplicating`() {
        val s = session(plugin(skill("session-skill", "file:///s/1/SKILL.md"), name = "P1"))
        val r = root(
            "copilotcli",
            plugin(skill("session-skill", "file:///s/1/SKILL.md"), name = "P1") + "," +
                plugin(skill("global-skill", "file:///s/2/SKILL.md"), name = "GlobalPlugin"),
        )

        val names = SlashCommands.of(s, root = r, includeBuiltIns = false).map { it.name }
        assertEquals(listOf("session-skill", "global-skill"), names)
    }

    // ---- SlashCommands matching & active detection ------------------------------------------------------------------

    @Test
    fun `typing slash filters and ranks prefix matches before substring and description matches with highlight ranges`() {
        val s = session(
            plugin(
                skill("update-skills", "file:///s/1/SKILL.md", ""","description":"Create or update repository skills"""") + "," +
                    skill("skill-kb", "file:///s/2/SKILL.md", ""","description":"Build a pack's KB"""") + "," +
                    skill("skill-eval", "file:///s/3/SKILL.md", ""","description":"Score a skill pack"""") + "," +
                    skill("audit-pack", "file:///s/4/SKILL.md", ""","description":"Run a skill routing check""""),
            ),
        )
        val commands = SlashCommands.of(s, includeBuiltIns = false)

        // Empty query after `/` returns all in order with no highlight
        val all = SlashCommands.match("/", commands)
        assertEquals(listOf("update-skills", "skill-kb", "skill-eval", "audit-pack"), all.map { it.command.name })
        assertEquals(0, all[0].highlightEnd)

        // `/skill` ranks prefix matches (`skill-kb`, `skill-eval`) before substring match (`update-skills`) before description match (`audit-pack`)
        val matched = SlashCommands.match("/skill", commands)
        assertEquals(listOf("skill-kb", "skill-eval", "update-skills", "audit-pack"), matched.map { it.command.name })
        // Prefix match highlights [0, 5)
        assertEquals(0 to 5, matched[0].highlightStart to matched[0].highlightEnd)
        // Substring match in `update-skills` highlights `skill` at [7, 12)
        assertEquals(7 to 12, matched[2].highlightStart to matched[2].highlightEnd)
        // Description match has empty name highlight [0, 0)
        assertEquals(0 to 0, matched[3].highlightStart to matched[3].highlightEnd)
    }

    @Test
    fun `multi-word built-in commands like skills list match across spaces while single-word commands close popup on space`() {
        val commands = SlashCommands.of(session(plugin(skill("skill-eval", "file:///s/1/SKILL.md"))), includeBuiltIns = true)

        // `/skills ` still matches multi-word subcommands `skills list` and `skills info`
        val subMatches = SlashCommands.match("/skills ", commands)
        assertEquals(listOf("skills list", "skills info"), subMatches.map { it.command.name })

        // `/skill-eval signals` has moved on to arguments, so popup match list is empty
        assertTrue(SlashCommands.match("/skill-eval signals", commands).isEmpty())

        // And `active` recognizes `/skill-eval` with arguments `"signals"`
        val active = SlashCommands.active("/skill-eval signals", commands)
        assertNotNull(active)
        assertEquals("skill-eval", active.command.name)
        assertEquals("signals", active.arguments)
    }

    @Test
    fun `matchAgentMention detects leading or trailing at-mentions and stripAgentMention removes the token`() {
        val agents = listOf(
            AgentChoice("file:///a/plan.agent.md", "plan", "Plan first"),
            AgentChoice("file:///a/review.agent.md", "review", "Review code"),
        )

        val leading = SlashCommands.matchAgentMention("@pl", agents)
        assertNotNull(leading)
        assertEquals(listOf("plan"), leading.matches.map { it.name })
        assertEquals("", SlashCommands.stripAgentMention("@pl", leading))

        val trailing = SlashCommands.matchAgentMention("check this @rev", agents)
        assertNotNull(trailing)
        assertEquals(listOf("review"), trailing.matches.map { it.name })
        assertEquals("check this", SlashCommands.stripAgentMention("check this @rev", trailing))

        assertNull(SlashCommands.matchAgentMention("email@example.com", agents))
    }

    // ---- CustomizationsView categorization & search -----------------------------------------------------------------

    @Test
    fun `customizationsOf categorizes plugins, mcp servers, skills, instructions, agents, hooks, and server tools`() {
        val children = listOf(
            skill("skill-eval", "file:///s/skill-eval/SKILL.md", ""","description":"Score a skill pack","_meta":{"ahpd.argumentHint":"[signals]"}"""),
            prompt("fix", "file:///p/fix.prompt.md", ""","description":"Fix issue""""),
            rule("kotlin-style", "file:///r/kotlin.instructions.md", ""","description":"Follow Kotlin conventions","alwaysApply":true,"globs":["**/*.kt"]"""),
            agent("planner", "file:///a/planner.agent.md", ""","description":"Plan work","model":"claude-opus-4-6","tools":["read","search"]"""),
            hook("pre-commit", "file:///h/pre-commit.sh"),
            mcp("github", "https://mcp.github.com", status = "ready"),
        ).joinToString(",")
        val topMcp = mcp("sqlite", "file:///mcp/sqlite", status = "error", extra = ""","error":{"errorType":"ConnectError","message":"Connection refused"}""")
        val toolsJson = """{"name":"bash","title":"Run Bash","description":"Execute shell command","inputSchema":{"type":"object"},"annotations":{"destructiveHint":true}}"""
        val s = session(
            customizations = plugin(children, name = "core-plugin", version = "1.2.0", load = """{"kind":"loaded"}""") + "," + topMcp,
            serverTools = toolsJson,
        )

        val view = SlashCommands.customizationsOf(s)
        assertFalse(view.isEmpty)
        assertEquals(1, view.countOf(CustomizationCategory.PLUGINS))
        assertEquals(2, view.countOf(CustomizationCategory.MCP_SERVERS))
        assertEquals(1, view.countOf(CustomizationCategory.SKILLS))
        assertEquals(2, view.countOf(CustomizationCategory.INSTRUCTIONS))
        assertEquals(1, view.countOf(CustomizationCategory.AGENTS))
        assertEquals(1, view.countOf(CustomizationCategory.HOOKS))
        assertEquals(1, view.countOf(CustomizationCategory.TOOLS))

        val skillEntry = view.entriesIn(CustomizationCategory.SKILLS).single()
        assertEquals("skill-eval", skillEntry.name)
        assertEquals("[signals]", skillEntry.argumentHint)
        assertTrue(skillEntry.canViewFile)

        val errorMcp = view.entriesIn(CustomizationCategory.MCP_SERVERS, query = "sqlite").single()
        assertTrue(errorMcp.isError)
        assertEquals("Error", errorMcp.statusLabel)
        assertEquals("Connection refused", errorMcp.description)

        val toolEntry = view.entriesIn(CustomizationCategory.TOOLS).single()
        assertEquals("bash", toolEntry.name)
        assertTrue("Destructive" in toolEntry.badges)
    }
}
