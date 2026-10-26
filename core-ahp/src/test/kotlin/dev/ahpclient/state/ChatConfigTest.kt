package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatConfigTest {
    private fun view(
        session: com.microsoft.agenthostprotocol.generated.SessionState? = configSession(),
        chat: String = CFG_CHAT,
        mutable: Set<String> = ALL_CHAT_MUTABLE,
        own: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ) = ChatConfig.view(session, chat, mutable, own)

    // ---- what is offered ---------------------------------------------------------------------------------------

    @Test
    fun `a host with mode and approvals offers both, in this app's own words`() {
        val view = view()

        assertEquals(listOf("Interactive", "Plan", "Autopilot"), view.mode!!.choices.map { it.label })
        assertEquals(listOf("Manual", "Assisted", "Allow all"), view.approvals!!.choices.map { it.label })
        assertEquals(listOf("interactive", "plan", "autopilot"), view.mode!!.choices.map { it.value })
        assertEquals(listOf("default", "assisted", "autoApprove"), view.approvals!!.choices.map { it.value })
        assertEquals("autoApprove", view.approvals!!.key)
        assertEquals("Mode", view.mode!!.title)
        assertEquals("Approvals", view.approvals!!.title)
    }

    @Test
    fun `the host's labels are not used, so a host cannot rename Allow all`() {
        val renamed = properties(
            "autoApprove" to AUTO_APPROVE_PROPERTY.replace("Allow all", "Safe mode").replace("Manual permissions", "Dangerous"),
        )

        val labels = view(configSession(properties = renamed, values = "")).approvals!!.choices.map { it.label }

        assertEquals(listOf("Manual", "Assisted", "Allow all"), labels)
    }

    @Test
    fun `the host's descriptions are kept as one short plain line`() {
        val long = "x".repeat(300)
        val noisy = properties("mode" to MODE_PROPERTY.replace("Plan first, execute when ready", "  Plan first,\\n  then act   $long "))

        val plan = view(configSession(properties = noisy, values = "")).mode!!.choices.first { it.value == "plan" }

        assertTrue(plan.description!!.length <= 80, plan.description)
        assertFalse(plan.description!!.contains("\n"))
        assertEquals("Step-by-step collaboration", view().mode!!.choices.first { it.value == "interactive" }.description)
    }

    @Test
    fun `a host with neither property offers nothing`() {
        val bare = configSession(properties = properties("sandboxEnabled" to """{"type":"string","title":"Sandbox","enum":["default","on","off"]}"""), values = "")

        assertEquals(ChatConfigView(null, null), view(bare))
        assertEquals(emptyList(), view(bare).controls)
    }

    @Test
    fun `no session state or no config offers nothing`() {
        assertEquals(ChatConfigView.NONE, view(session = null))
        val noConfig = com.microsoft.agenthostprotocol.Ahp.json.decodeFromString(
            com.microsoft.agenthostprotocol.generated.SessionState.serializer(),
            """{"provider":"copilotcli","title":"t","status":1,"lifecycle":"ready","activeClients":[],"chats":[]}""",
        )
        assertEquals(ChatConfigView.NONE, view(session = noConfig))
    }

    @Test
    fun `a host without autoApprove but with approvalMode is read through that name`() {
        val copilot = configSession(properties = properties("approvalMode" to APPROVAL_MODE_PROPERTY), values = """"approvalMode":"allow-all"""")

        val approvals = view(copilot).approvals!!

        assertEquals("approvalMode", approvals.key)
        assertEquals(listOf("Manual", "Assisted", "Allow all"), approvals.choices.map { it.label })
        assertEquals("allow-all", approvals.current)
        assertEquals("Allow all", approvals.currentLabel)
        assertTrue(approvals.loosens("allow-all").not(), "already there")
        assertTrue(approvals.copy(current = "manual").loosens("allow-all"))
    }

    @Test
    fun `when both names exist autoApprove wins`() {
        val both = configSession(
            properties = properties("autoApprove" to AUTO_APPROVE_PROPERTY, "approvalMode" to APPROVAL_MODE_PROPERTY),
            values = """"autoApprove":"assisted","approvalMode":"allow-all"""",
        )

        val approvals = view(both).approvals!!

        assertEquals("autoApprove", approvals.key)
        assertEquals("assisted", approvals.current)
    }

    @Test
    fun `a property of the wrong shape is not offered`() {
        val dynamic = properties("mode" to MODE_PROPERTY.replace("\"type\":\"string\"", "\"type\":\"string\",\"enumDynamic\":true"))
        val notString = properties("mode" to MODE_PROPERTY.replace("\"type\":\"string\"", "\"type\":\"number\""))
        val noBase = properties("mode" to MODE_PROPERTY.replace("\"enum\":[\"interactive\",\"plan\",\"autopilot\"]", "\"enum\":[\"plan\",\"autopilot\"]"), "autoApprove" to AUTO_APPROVE_PROPERTY.replace("\"enum\":[\"default\",\"assisted\",\"autoApprove\"]", "\"enum\":[\"assisted\",\"autoApprove\"]"))

        assertNull(view(configSession(properties = dynamic, values = "")).mode)
        assertNull(view(configSession(properties = notString, values = "")).mode)
        val missing = view(configSession(properties = noBase, values = ""))
        assertNull(missing.mode, "no interactive value")
        assertNull(missing.approvals, "no default value")
    }

    @Test
    fun `a value this app does not know is not offered`() {
        val extra = properties(
            "mode" to MODE_PROPERTY.replace("\"autopilot\"]", "\"autopilot\",\"yolo\"]"),
            "autoApprove" to AUTO_APPROVE_PROPERTY.replace("\"autoApprove\"]", "\"autoApprove\",\"bypass\"]"),
        )

        val view = view(configSession(properties = extra, values = ""))

        assertEquals(listOf("interactive", "plan", "autopilot"), view.mode!!.choices.map { it.value })
        assertEquals(listOf("default", "assisted", "autoApprove"), view.approvals!!.choices.map { it.value })
    }

    // ---- what is in force --------------------------------------------------------------------------------------

    @Test
    fun `with no values the schema's defaults are in force`() {
        val view = view(configSession(values = ""))

        assertEquals("interactive", view.mode!!.current)
        assertEquals("default", view.approvals!!.current)
        assertEquals(emptyList(), view.notable)
    }

    @Test
    fun `the session's values are in force for a chat with none of its own`() {
        val view = view(configSession(values = """"autoApprove":"autoApprove","mode":"plan""""))

        assertEquals("plan", view.mode!!.current)
        assertEquals("Allow all", view.approvals!!.currentLabel)
        assertEquals(listOf("mode", "autoApprove"), view.notable.map { it.key })
    }

    @Test
    fun `a chat's own value hides the session's`() {
        val session = configSession(values = """"autoApprove":"default","mode":"interactive"""")

        val view = view(session, own = own("autoApprove" to "autoApprove", "mode" to "plan"))

        assertEquals("autoApprove", view.approvals!!.current)
        assertEquals("plan", view.mode!!.current)
    }

    @Test
    fun `a chat's own value for a property it may not set is ignored`() {
        val session = configSession(values = """"autoApprove":"default","mode":"interactive"""")

        val view = view(session, mutable = setOf("mode"), own = own("autoApprove" to "autoApprove", "mode" to "plan"))

        assertEquals("default", view.approvals!!.current, "autoApprove is not chat-mutable here")
        assertEquals("plan", view.mode!!.current)
    }

    @Test
    fun `a host's report of the approval level in force wins over the property`() {
        val props = properties("autoApprove" to AUTO_APPROVE_PROPERTY, "effectiveApprovalMode" to EFFECTIVE_APPROVAL_PROPERTY)
        val session = configSession(props, values = """"autoApprove":"default","effectiveApprovalMode":"assisted"""")

        assertEquals("assisted", view(session).approvals!!.current)
    }

    @Test
    fun `the session's report does not apply once the chat chose its own approvals`() {
        val props = properties("autoApprove" to AUTO_APPROVE_PROPERTY, "effectiveApprovalMode" to EFFECTIVE_APPROVAL_PROPERTY)
        val session = configSession(props, values = """"autoApprove":"default","effectiveApprovalMode":"assisted"""")

        val view = view(session, own = own("autoApprove" to "autoApprove"))

        assertEquals("autoApprove", view.approvals!!.current)
    }

    @Test
    fun `the chat's own report of the level in force is used`() {
        val props = properties("autoApprove" to AUTO_APPROVE_PROPERTY, "effectiveApprovalMode" to EFFECTIVE_APPROVAL_PROPERTY)
        val session = configSession(props, values = """"autoApprove":"default","effectiveApprovalMode":"default"""")

        val view = view(session, own = own("autoApprove" to "autoApprove", "effectiveApprovalMode" to "autoApprove"))

        assertEquals("autoApprove", view.approvals!!.current)
    }

    @Test
    fun `a report that is not marked read only is not believed`() {
        val props = properties("autoApprove" to AUTO_APPROVE_PROPERTY, "effectiveApprovalMode" to EFFECTIVE_APPROVAL_PROPERTY.replace(",\"readOnly\":true", ""))
        val session = configSession(props, values = """"autoApprove":"default","effectiveApprovalMode":"autoApprove"""")

        assertEquals("default", view(session).approvals!!.current)
    }

    @Test
    fun `a current value that is not one this app knows is unknown, and points nothing out`() {
        val view = view(configSession(values = """"autoApprove":"bypass","mode":"yolo""""))

        assertNull(view.mode!!.current)
        assertNull(view.approvals!!.current)
        assertNull(view.approvals!!.currentLabel)
        assertEquals(emptyList(), view.notable)
    }

    // ---- what the host allows now ------------------------------------------------------------------------------

    @Test
    fun `levels the host withholds are shown, and cannot be chosen`() {
        val props = properties("autoApprove" to AUTO_APPROVE_PROPERTY, "availableApprovalModes" to AVAILABLE_APPROVAL_PROPERTY)
        val session = configSession(props, values = """"autoApprove":"default","availableApprovalModes":["default","assisted"]""")

        val approvals = view(session).approvals!!

        assertEquals(listOf(true, true, false), approvals.choices.map { it.available })
        assertTrue(approvals.offers("assisted"))
        assertFalse(approvals.offers("autoApprove"))
    }

    @Test
    fun `a list of allowed levels that is not marked read only is not believed`() {
        val props = properties("autoApprove" to AUTO_APPROVE_PROPERTY, "availableApprovalModes" to AVAILABLE_APPROVAL_PROPERTY.replace(",\"readOnly\":true", ""))
        val session = configSession(props, values = """"autoApprove":"default","availableApprovalModes":["default"]""")

        assertEquals(listOf(true, true, true), view(session).approvals!!.choices.map { it.available })
    }

    // ---- what may be changed -----------------------------------------------------------------------------------

    @Test
    fun `a chat the session lists may set its own mode and approvals`() {
        val view = view()

        assertTrue(view.mode!!.writable)
        assertTrue(view.approvals!!.writable)
    }

    @Test
    fun `a property the schema does not let a chat set is shown but not writable`() {
        val view = view(mutable = setOf("mode"))

        assertTrue(view.mode!!.writable)
        assertFalse(view.approvals!!.writable)
        assertEquals("default", view.approvals!!.current)
    }

    @Test
    fun `a read only property is not writable`() {
        val props = properties("autoApprove" to AUTO_APPROVE_PROPERTY.replace("\"chatMutable\":true", "\"chatMutable\":true,\"readOnly\":true"), "mode" to MODE_PROPERTY)

        val view = view(configSession(props, values = ""))

        assertFalse(view.approvals!!.writable)
        assertTrue(view.mode!!.writable)
    }

    @Test
    fun `a chat the session does not list is not writable`() {
        assertFalse(view(chat = "ahp-chat:/unknown").mode!!.writable)
    }

    @Test
    fun `a subagent's chat, started by a tool, is not writable, and neither is one of an origin this app does not know`() {
        val chats = """[
            {"resource":"$CFG_CHAT","title":"Main","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z"},
            {"resource":"$CFG_SUBAGENT","title":"Sub","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z","origin":{"kind":"tool","chat":"$CFG_CHAT","toolCallId":"t1"}},
            {"resource":"ahp-chat:/odd","title":"Odd","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z","origin":{"kind":"something-new"}},
            {"resource":"ahp-chat:/fork","title":"Fork","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z","origin":{"kind":"fork","chat":"$CFG_CHAT","turnId":"t1"}}
        ]"""
        val session = configSession(chats = chats)

        assertTrue(view(session, CFG_CHAT).mode!!.writable)
        assertFalse(view(session, CFG_SUBAGENT).mode!!.writable)
        assertFalse(view(session, "ahp-chat:/odd").mode!!.writable)
        assertTrue(view(session, "ahp-chat:/fork").mode!!.writable)
    }

    // ---- loosening ---------------------------------------------------------------------------------------------

    @Test
    fun `more freedom for the agent is loosening, less is not`() {
        val approvals = view().approvals!!
        assertTrue(approvals.loosens("assisted"))
        assertTrue(approvals.loosens("autoApprove"))
        assertFalse(approvals.loosens("default"))

        val allowAll = approvals.copy(current = "autoApprove")
        assertFalse(allowAll.loosens("assisted"))
        assertFalse(allowAll.loosens("default"))
        assertFalse(allowAll.loosens("autoApprove"))

        val assisted = approvals.copy(current = "assisted")
        assertTrue(assisted.loosens("autoApprove"))
        assertFalse(assisted.loosens("default"))
    }

    @Test
    fun `only autopilot loosens a mode`() {
        val mode = view().mode!!

        assertTrue(mode.loosens("autopilot"))
        assertFalse(mode.loosens("plan"))
        assertFalse(mode.loosens("interactive"))
        assertFalse(mode.copy(current = "plan").loosens("interactive"))
        assertTrue(mode.copy(current = "plan").loosens("autopilot"))
        assertFalse(mode.copy(current = "autopilot").loosens("plan"))
        assertFalse(mode.copy(current = "autopilot").loosens("autopilot"))
    }

    @Test
    fun `an unknown current value counts as the least, so any step up is loosening`() {
        val unknown = view().approvals!!.copy(current = null)

        assertTrue(unknown.loosens("autoApprove"))
        assertTrue(unknown.loosens("assisted"))
        assertFalse(unknown.loosens("default"))
    }

    @Test
    fun `a value that is not one of the words is never loosening`() {
        assertFalse(view().approvals!!.loosens("bypass"))
        assertFalse(view().mode!!.loosens("yolo"))
    }

    // ---- what to point out -------------------------------------------------------------------------------------

    @Test
    fun `plan, autopilot, assisted and allow all are pointed out, and the quiet defaults are not`() {
        fun notable(mode: String, approvals: String) =
            view(configSession(values = """"mode":"$mode","autoApprove":"$approvals"""")).notable.map { it.currentLabel }

        assertEquals(emptyList(), notable("interactive", "default"))
        assertEquals(listOf("Plan"), notable("plan", "default"))
        assertEquals(listOf("Autopilot"), notable("autopilot", "default"))
        assertEquals(listOf("Assisted"), notable("interactive", "assisted"))
        assertEquals(listOf("Allow all"), notable("interactive", "autoApprove"))
        assertEquals(listOf("Plan", "Allow all"), notable("plan", "autoApprove"))
    }

    @Test
    fun `control looks a setting up by its key`() {
        val view = view()

        assertEquals("mode", view.control("mode")!!.key)
        assertEquals("autoApprove", view.control("autoApprove")!!.key)
        assertNull(view.control("sandboxEnabled"))
        assertNull(view.control("permissions"))
    }
}
