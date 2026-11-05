package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ActionType
import com.microsoft.agenthostprotocol.generated.AutomationCancelledRunLifecycle
import com.microsoft.agenthostprotocol.generated.AutomationCapabilities
import com.microsoft.agenthostprotocol.generated.AutomationCompletedRunLifecycle
import com.microsoft.agenthostprotocol.generated.AutomationDefinitionPatch
import com.microsoft.agenthostprotocol.generated.AutomationEntry
import com.microsoft.agenthostprotocol.generated.AutomationEventTrigger
import com.microsoft.agenthostprotocol.generated.AutomationFailedRunLifecycle
import com.microsoft.agenthostprotocol.generated.AutomationOperation
import com.microsoft.agenthostprotocol.generated.AutomationPendingRunLifecycle
import com.microsoft.agenthostprotocol.generated.AutomationRunCancelRequestedAction
import com.microsoft.agenthostprotocol.generated.AutomationRunLifecycle
import com.microsoft.agenthostprotocol.generated.AutomationRunSummary
import com.microsoft.agenthostprotocol.generated.AutomationRunningRunLifecycle
import com.microsoft.agenthostprotocol.generated.AutomationScheduleTrigger
import com.microsoft.agenthostprotocol.generated.AutomationState
import com.microsoft.agenthostprotocol.generated.AutomationTrigger
import com.microsoft.agenthostprotocol.generated.AutomationUpdateRequestedAction
import com.microsoft.agenthostprotocol.generated.StateAction
import com.microsoft.agenthostprotocol.generated.StateActionAutomationRunCancelRequested
import com.microsoft.agenthostprotocol.generated.StateActionAutomationUpdateRequested
import com.microsoft.agenthostprotocol.generated.AutomationRunLifecycleCancelled
import com.microsoft.agenthostprotocol.generated.AutomationRunLifecycleCompleted
import com.microsoft.agenthostprotocol.generated.AutomationRunLifecycleFailed
import com.microsoft.agenthostprotocol.generated.AutomationRunLifecyclePending
import com.microsoft.agenthostprotocol.generated.AutomationRunLifecycleRunning
import com.microsoft.agenthostprotocol.generated.AutomationTriggerEvent
import com.microsoft.agenthostprotocol.generated.AutomationTriggerSchedule
import dev.ahpclient.platform.DateTimeException
import dev.ahpclient.platform.Instant
import dev.ahpclient.platform.durationBetween
import dev.ahpclient.platform.parseInstant
import dev.ahpclient.platform.seconds

/**
 * The host's automations (P7): its catalogue channel, what the catalogue shows as rows, and the two things this app may ask of it,
 * turning an automation's triggers on or off and cancelling a run. Pure: nothing here talks to the host.
 */
object Automations {
    /** The catalogue channel, as the protocol names it. */
    const val CHANNEL = "ahp-automations://"

    /** How many runs an automation's row lists, newest first; the host keeps more. */
    const val SHOWN_RUNS = 5

    /**
     * [uri] as this app keys the catalogue: VS Code writes the channel as `ahp-automations:`, without the slashes the protocol gives
     * it, in its replies and actions. Both are the one catalogue; any other address is returned as it is.
     */
    fun canonical(uri: String): String = if (uri == "ahp-automations:") CHANNEL else uri

    /** The catalogue as rows, in the host's order. [now] is for "next run in …" and "… ago". */
    fun rows(state: AutomationState, now: Instant, capabilities: AutomationCapabilities?): List<AutomationRow> =
        state.entries.map { row(it, now, capabilities) }

    internal fun row(entry: AutomationEntry, now: Instant, capabilities: AutomationCapabilities?): AutomationRow {
        val definition = entry.definition
        val canUpdate = AutomationOperation.UPDATE in entry.operations
        return AutomationRow(
            resource = entry.resource,
            title = definition.title.ifBlank { "Automation" },
            triggers = triggersText(definition.triggers),
            enabled = definition.enabled,
            canToggle = canUpdate,
            nextRun = entry.nextRunAt?.takeIf { definition.enabled }?.let { untilText(it, now) }?.let { "Next run $it" },
            runs = entry.runs.take(SHOWN_RUNS).map { run(it, now, cancellable = capabilities?.runCancellation != null) },
        )
    }

    private fun run(run: AutomationRunSummary, now: Instant, cancellable: Boolean): AutomationRunRow {
        val (status, at) = when (val lifecycle = run.lifecycle) {
            is AutomationRunLifecyclePending -> "Waiting to start" to lifecycle.value.createdAt
            is AutomationRunLifecycleRunning -> "Running" to lifecycle.value.startedAt
            is AutomationRunLifecycleCompleted -> "Done" to lifecycle.value.completedAt
            is AutomationRunLifecycleFailed -> "Failed" to lifecycle.value.completedAt
            is AutomationRunLifecycleCancelled -> "Cancelled" to lifecycle.value.completedAt
            else -> "Unknown" to null
        }
        val active = run.lifecycle is AutomationRunLifecyclePending || run.lifecycle is AutomationRunLifecycleRunning
        return AutomationRunRow(
            resource = run.resource,
            status = status,
            whenText = at?.let { RelativeTime.format(it, now) }?.takeIf { it.isNotEmpty() },
            primarySession = run.primarySession,
            canCancel = active && cancellable,
        )
    }

    /** What sets an automation off, in words: manual only, a schedule, or a host event, several joined. */
    internal fun triggersText(triggers: List<AutomationTrigger>): String {
        if (triggers.isEmpty()) return "Manual only"
        return triggers.joinToString("; ") { trigger ->
            when (trigger) {
                is AutomationTriggerSchedule -> scheduleText(trigger.value)
                is AutomationTriggerEvent -> eventText(trigger.value)
                else -> "Another kind of trigger"
            }
        }
    }

    private fun scheduleText(trigger: AutomationScheduleTrigger): String =
        "${Cron.describe(trigger.schedule.expression)} (${trigger.schedule.timeZone})"

    private fun eventText(trigger: AutomationEventTrigger): String = "When ${trigger.title.ifBlank { trigger.type }}"

    /** Turns an automation's triggers on or off. A manual run is still offered either way; the host keeps the rest of its definition. */
    fun setEnabled(resource: String, enabled: Boolean): StateAction =
        StateActionAutomationUpdateRequested(
            AutomationUpdateRequestedAction(type = ActionType.AUTOMATION_UPDATE_REQUESTED, resource = resource, changes = AutomationDefinitionPatch(enabled = enabled)),
        )

    /** Asks the host to cancel a run. Sent on the run's own channel, which names it. */
    fun cancelRun(): StateAction = StateActionAutomationRunCancelRequested(AutomationRunCancelRequestedAction(type = ActionType.AUTOMATION_RUN_CANCEL_REQUESTED))

    /** How long until [iso]: "in 14h", "in 5m", "now" once it is due; null when unreadable. */
    internal fun untilText(iso: String, now: Instant): String? {
        val then = try {
            parseInstant(iso)
        } catch (_: DateTimeException) {
            return null
        }
        val seconds = durationBetween(now, then).seconds
        return when {
            seconds < 60 -> "now"
            seconds < 3_600 -> "in ${seconds / 60}m"
            seconds < 86_400 -> "in ${seconds / 3_600}h"
            else -> "in ${seconds / 86_400}d"
        }
    }
}

/** One automation as the catalogue shows it. */
data class AutomationRow(
    val resource: String,
    val title: String,
    /** What sets it off: "Weekdays at 06:00 (America/Los_Angeles)", "Manual only". */
    val triggers: String,
    val enabled: Boolean,
    /** Whether the host lets this automation be changed; the switch is offered only then, and only on the manage tier. */
    val canToggle: Boolean,
    /** "Next run in 14h", or null when none is scheduled or the automation is off. */
    val nextRun: String?,
    val runs: List<AutomationRunRow>,
) {
    /** What a screen reader says for the row before its runs. */
    val spoken: String get() = listOfNotNull(title, if (enabled) "On" else "Off", triggers, nextRun).joinToString(", ")
}

/** One recent run of an automation. */
data class AutomationRunRow(
    val resource: String,
    /** "Running", "Done", "Failed", …. */
    val status: String,
    /** How long ago, as a list row says it ("5m", "2h", "3d"), or null when the host did not say. */
    val whenText: String?,
    /** The session the run started, to open; null before it has one. */
    val primarySession: String?,
    /** Waiting or running, and the host takes cancellations. */
    val canCancel: Boolean,
) {
    val spoken: String get() = listOfNotNull(status, whenText).joinToString(", ")
}

/**
 * A short reading of a five-field AHP cron expression (`minute hour day-of-month month day-of-week`) for the common shapes:
 * a time on every day, on weekdays, or on given days of the week, and every N minutes or hours. Anything else is shown as written,
 * which is always true. Pure.
 */
object Cron {
    private val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    fun describe(expression: String): String {
        val fields = expression.trim().split(Regex("\\s+"))
        if (fields.size != 5) return "Schedule $expression"
        val (minute, hour, dayOfMonth, month, dayOfWeek) = fields
        if (dayOfMonth != "*" || month != "*") return "Schedule $expression"
        stepOf(minute)?.let { n -> if (hour == "*" && dayOfWeek == "*") return "Every $n min" }
        if (minute.toIntOrNull() != null) stepOf(hour)?.let { n -> if (dayOfWeek == "*") return "Every $n h at :${minute.padStart(2, '0')}" }
        val m = minute.toIntOrNull()?.takeIf { it in 0..59 } ?: return "Schedule $expression"
        val h = hour.toIntOrNull()?.takeIf { it in 0..23 } ?: return "Schedule $expression"
        val time = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
        val days = when (dayOfWeek) {
            "*" -> "Every day"
            "1-5" -> "Weekdays"
            "0,6", "6,0" -> "Weekends"
            else -> daysOf(dayOfWeek)?.joinToString(", ") ?: return "Schedule $expression"
        }
        return "$days at $time"
    }

    /** N from `*``/N`, or null. */
    private fun stepOf(field: String): Int? = field.removePrefix("*/").takeIf { field.startsWith("*/") }?.toIntOrNull()?.takeIf { it > 0 }

    /** Day names from a comma-separated list of single days 0 to 7 (both 0 and 7 are Sunday), or null for anything else. */
    private fun daysOf(field: String): List<String>? = field.split(',').map { part -> part.toIntOrNull()?.takeIf { it in 0..7 }?.let { DAYS[it % 7] } ?: return null }
}
