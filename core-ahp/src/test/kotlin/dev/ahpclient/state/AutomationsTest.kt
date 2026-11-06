package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.AutomationCapabilities
import com.microsoft.agenthostprotocol.generated.AutomationState
import com.microsoft.agenthostprotocol.generated.StateAction
import dev.ahpclient.ahp.CapabilityDeniedException
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.platform.parseInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

internal const val AUTOMATIONS_CAPABILITY = """{"create":{},"schedules":{},"runCancellation":{},"runHistoryLimit":50}"""

/** A run summary, as the catalogue lists it. [lifecycle] is the JSON of its lifecycle. */
internal fun automationRunJson(id: String, lifecycle: String, session: String? = null) =
    """{"resource":"ahp-automation-run:/$id","automation":"ahp-automation:/a1","origin":{"kind":"trigger","triggerId":"schedule"},"lifecycle":$lifecycle,${session?.let { """"primarySession":"$it",""" }.orEmpty()}"sessionCount":${if (session != null) 1 else 0}}"""

internal const val RUNNING = """{"status":"running","createdAt":"2026-11-05T13:00:00.000Z","startedAt":"2026-11-05T13:00:01.000Z"}"""
internal const val COMPLETED = """{"status":"completed","createdAt":"2026-11-04T14:00:00.000Z","startedAt":"2026-11-04T14:00:01.000Z","completedAt":"2026-11-04T14:15:00.000Z"}"""
internal const val FAILED = """{"status":"failed","createdAt":"2026-11-03T14:00:00.000Z","completedAt":"2026-11-03T14:01:00.000Z","error":{"errorType":"E","message":"broke"}}"""

/** One automation as the catalogue holds it: a weekday 06:00 schedule in Los Angeles, with the given runs and operations. */
internal fun automationEntryJson(
    id: String = "a1",
    title: String = "Morning digest",
    enabled: Boolean = true,
    triggers: String = """[{"id":"schedule","kind":"schedule","schedule":{"expression":"0 6 * * 1-5","timeZone":"America/Los_Angeles"},"misfirePolicy":"runOnce"}]""",
    runs: List<String> = emptyList(),
    operations: String = """["update","remove","run"]""",
    nextRunAt: String? = "2026-11-06T14:00:00.000Z",
) = """{"resource":"ahp-automation:/$id","definition":{"title":"$title","message":{"text":"Summarise yesterday's sessions","origin":{"kind":"user"}},"session":{"provider":"copilotcli"},"enabled":$enabled,"triggers":$triggers},${nextRunAt?.let { """"nextRunAt":"$it",""" }.orEmpty()}"runs":[${runs.joinToString(",")}],"operations":$operations,"createdAt":"2026-11-01T10:00:00.000Z","modifiedAt":"2026-11-05T10:00:00.000Z"}"""

/** The catalogue (P7), read into rows; the two actions; and the policy that holds the update to on or off. */
class AutomationsTest {
    private val now = parseInstant("2026-11-05T14:00:00.000Z")
    private val capabilities = Ahp.json.decodeFromString(AutomationCapabilities.serializer(), AUTOMATIONS_CAPABILITY)

    private fun state(vararg entries: String) = Ahp.json.decodeFromString(AutomationState.serializer(), """{"entries":[${entries.joinToString(",")}]}""")

    @Test
    fun `an automation reads as its title, what sets it off, whether it is on and when it next runs`() {
        val row = Automations.rows(state(automationEntryJson()), now, capabilities).single()

        assertEquals("Morning digest", row.title)
        assertEquals("Weekdays at 06:00 (America/Los_Angeles)", row.triggers)
        assertTrue(row.enabled)
        assertTrue(row.canToggle)
        assertTrue(row.triggered, "a schedule sets it off by itself")
        assertEquals("Next run in 1d", row.nextRun)
        assertEquals("Morning digest, On, Weekdays at 06:00 (America/Los_Angeles), Next run in 1d", row.spoken)
    }

    @Test
    fun `one that is off shows no next run, and one without the update operation cannot be switched`() {
        assertNull(Automations.rows(state(automationEntryJson(enabled = false)), now, capabilities).single().nextRun)
        assertFalse(Automations.rows(state(automationEntryJson(operations = """["run"]""")), now, capabilities).single().canToggle)
        val manual = Automations.rows(state(automationEntryJson(triggers = "[]")), now, capabilities).single()
        assertEquals("Manual only", manual.triggers)
        assertFalse(manual.triggered, "nothing sets a manual-only one off")
    }

    @Test
    fun `runs read newest first with what became of them, and only a running one on a host that takes cancellations can be cancelled`() {
        val entry = automationEntryJson(runs = listOf(automationRunJson("r3", RUNNING, "ahp-session:/s3"), automationRunJson("r2", COMPLETED, "ahp-session:/s2"), automationRunJson("r1", FAILED)))
        val runs = Automations.rows(state(entry), now, capabilities).single().runs

        assertEquals(listOf("Running", "Done", "Failed"), runs.map { it.status })
        assertEquals(listOf("59m", "23h", "2d"), runs.map { it.whenText })
        assertEquals(listOf(true, false, false), runs.map { it.canCancel })
        assertEquals("ahp-session:/s3", runs[0].primarySession)
        assertNull(runs[2].primarySession)

        val noCancellation = Ahp.json.decodeFromString(AutomationCapabilities.serializer(), """{"create":{}}""")
        assertFalse(Automations.rows(state(entry), now, noCancellation).single().runs[0].canCancel)
    }

    @Test
    fun `no more than the shown runs are listed`() {
        val runs = (1..9).map { automationRunJson("r$it", COMPLETED) }
        assertEquals(Automations.SHOWN_RUNS, Automations.rows(state(automationEntryJson(runs = runs)), now, capabilities).single().runs.size)
    }

    @Test
    fun `the common cron shapes read in words, and anything else as it is written`() {
        assertEquals("Every day at 09:30", Cron.describe("30 9 * * *"))
        assertEquals("Weekdays at 06:00", Cron.describe("0 6 * * 1-5"))
        assertEquals("Weekends at 10:05", Cron.describe("5 10 * * 0,6"))
        assertEquals("Mon, Wed, Fri at 08:00", Cron.describe("0 8 * * 1,3,5"))
        assertEquals("Sun at 08:00", Cron.describe("0 8 * * 7"))
        assertEquals("Every 15 min", Cron.describe("*/15 * * * *"))
        assertEquals("Every 2 h at :00", Cron.describe("0 */2 * * *"))
        assertEquals("Schedule 0 6 1 * *", Cron.describe("0 6 1 * *"))
        assertEquals("Schedule 0 25 * * *", Cron.describe("0 25 * * *"))
        assertEquals("Schedule nonsense", Cron.describe("nonsense"))
    }

    @Test
    fun `the catalogue is one channel however a host writes its address`() {
        assertEquals(Automations.CHANNEL, Automations.canonical("ahp-automations:"))
        assertEquals(Automations.CHANNEL, Automations.canonical("ahp-automations://"))
        assertEquals("ahp-session:/x", Automations.canonical("ahp-session:/x"))
    }

    @Test
    fun `turning one off sends only its enabled flag, and cancelling names only the type`() {
        val update = Ahp.json.encodeToJsonElement(StateAction.serializer(), Automations.setEnabled("ahp-automation:/a1", false)).jsonObject
        assertEquals("""{"type":"automation/updateRequested","resource":"ahp-automation:/a1","changes":{"enabled":false}}""", update.toString())
        val cancel = Ahp.json.encodeToJsonElement(StateAction.serializer(), Automations.cancelRun()).jsonObject
        assertEquals("""{"type":"automationRun/cancelRequested"}""", cancel.toString())
    }

    @Test
    fun `the policy lets an update through only when it changes nothing but enabled`() {
        val policy = CapabilityPolicy.MANAGE
        fun check(json: String) = policy.checkContent("automation/updateRequested", Ahp.json.parseToJsonElement(json).jsonObject)

        check("""{"type":"automation/updateRequested","resource":"ahp-automation:/a1","changes":{"enabled":true}}""")
        listOf(
            """{"type":"automation/updateRequested","resource":"ahp-automation:/a1","changes":{"enabled":false,"title":"x"}}""",
            """{"type":"automation/updateRequested","resource":"ahp-automation:/a1","changes":{"message":{"text":"rm -rf"}}}""",
            """{"type":"automation/updateRequested","resource":"ahp-automation:/a1","changes":{"enabled":"false"}}""",
            """{"type":"automation/updateRequested","resource":"ahp-automation:/a1","changes":{}}""",
            """{"type":"automation/updateRequested","resource":"ahp-automation:/a1"}""",
            """{"type":"automation/updateRequested","resource":"ahp-automation:/a1","changes":{"enabled":true},"extra":1}""",
        ).forEach { json -> assertFailsWith<CapabilityDeniedException>(json) { check(json) } }
    }
}
