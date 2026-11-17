package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where each of the host's new-session settings goes on the page, and what its chip says. */
class NewSessionSetupTest {
    private fun text(id: String, title: String, vararg options: Pair<String, String>, value: String? = options.firstOrNull()?.first, dynamic: Boolean = false) =
        ConfigField(id, title, null, options.map { (v, l) -> ConfigOption(ConfigPick.Text(v), l) }, required = false, value = value?.let(ConfigPick::Text), dynamic = dynamic)

    private val sandbox = ConfigField("sandbox", "Sandbox", null, listOf(ConfigOption(ConfigPick.Flag(true), "On"), ConfigOption(ConfigPick.Flag(false), "Off")), false, ConfigPick.Flag(false))
    private val branch = text("branch", "Branch", "main" to "main", dynamic = true)
    private val isolation = text("isolation", "Isolation", "folder" to "Folder", "worktree" to "Worktree", value = "worktree")
    private val mode = text("mode", "Mode", "interactive" to "Interactive", "plan" to "Plan", "autopilot" to "Autopilot")
    private val approvals = text("autoApprove", "Approvals", "default" to "Manual", "assisted" to "Assisted", "autoApprove" to "Allow all")

    private val form = SessionConfigForm(listOf(approvals, sandbox, branch, mode, isolation), omitted = 0, resolved = emptyMap())

    @Test
    fun `mode and approvals go to the message box and the rest are setup chips, isolation and branch first`() {
        assertEquals(listOf("isolation", "branch", "sandbox"), NewSessionSetup.setupFields(form).map { it.id })
        assertEquals(listOf("mode", "autoApprove"), NewSessionSetup.chatFields(form).map { it.id })
    }

    @Test
    fun `no form means nothing to show`() {
        assertEquals(emptyList(), NewSessionSetup.setupFields(null))
        assertNull(NewSessionSetup.chatLabel(null, emptyMap()))
    }

    @Test
    fun `a chip shows the person's pick over the host's value, and a completed value by its text`() {
        assertEquals("Worktree", NewSessionSetup.valueLabel(isolation, emptyMap()))
        assertEquals("Folder", NewSessionSetup.valueLabel(isolation, mapOf("isolation" to ConfigPick.Text("folder"))))
        assertEquals("feature-x", NewSessionSetup.valueLabel(branch, mapOf("branch" to ConfigPick.Text("feature-x"))))
        assertEquals("Off", NewSessionSetup.valueLabel(sandbox, emptyMap()))
    }

    @Test
    fun `the mode chip names the mode and the approvals together`() {
        assertEquals("Interactive · Manual", NewSessionSetup.chatLabel(form, emptyMap()))
        assertEquals("Plan · Assisted", NewSessionSetup.chatLabel(form, mapOf("mode" to ConfigPick.Text("plan"), "autoApprove" to ConfigPick.Text("assisted"))))
    }

    @Test
    fun `allow all is told apart, by either name the host uses`() {
        assertFalse(NewSessionSetup.allowsAll(form, emptyMap()))
        assertTrue(NewSessionSetup.allowsAll(form, mapOf("autoApprove" to ConfigPick.Text("autoApprove"))))
        val copilot = SessionConfigForm(listOf(text("approvalMode", "Approvals", "manual" to "Manual", "allow-all" to "Allow all", value = "allow-all")), 0, emptyMap())
        assertTrue(NewSessionSetup.allowsAll(copilot, emptyMap()))
        // Autopilot is a mode, not an approval level.
        assertFalse(NewSessionSetup.allowsAll(form, mapOf("mode" to ConfigPick.Text("autopilot"))))
    }

    @Test
    fun `the page opens on the most recent folder and the host's first agent`() {
        assertEquals("file:///a", NewSessionSetup.defaultFolder(listOf("file:///a", "file:///b")))
        assertNull(NewSessionSetup.defaultFolder(emptyList()))
        assertEquals("copilotcli", NewSessionSetup.defaultProvider(listOf("copilotcli", "claude")))
    }
}
