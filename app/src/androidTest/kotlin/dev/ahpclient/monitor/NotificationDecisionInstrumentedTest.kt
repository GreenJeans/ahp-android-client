package dev.ahpclient.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahpclient.state.ApprovalTarget
import dev.ahpclient.state.DecisionOption
import dev.ahpclient.state.InputNeeded
import dev.ahpclient.state.QuestionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Approve and Deny on a notification: when they exist, that no button can reach another's call, and that nothing outside the app can press them. */
@RunWith(AndroidJUnit4::class)
class NotificationDecisionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    private val target = ApprovalTarget("ahp-chat://default/x", "call-1")

    private val withOptions = target.copy(
        options = listOf(
            DecisionOption("o-no", "Skip", approves = false),
            DecisionOption("o-once", "Allow once", approves = true),
            DecisionOption("o-session", "Allow in this session", approves = true),
        ),
    )

    // Cancelling is asynchronous, so a notification from an earlier test may still be up: every test uses tags of its own.
    private val run = System.nanoTime()

    private fun tag(name: String) = "ahp-session:/$run-$name"

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        manager.cancelAll()
    }

    private fun shown(tag: String): StatusBarNotification {
        repeat(30) {
            manager.activeNotifications.firstOrNull { it.tag == tag }?.let { return it }
            Thread.sleep(100)
        }
        error("the notification was never posted")
    }

    private fun titles(notification: Notification): List<String> = notification.actions.orEmpty().map { it.title.toString() }

    private fun post(resource: String, approval: ApprovalTarget?, canDecide: Boolean = true): Notification {
        val unique = tag(resource.removePrefix("ahp-session:/"))
        NotificationInputNeededSink(context, canDecide = { canDecide }).post(InputNeeded(unique, "Fix the build", "Run command: Run the tests", approval))
        return shown(unique).notification
    }

    @Test
    fun aPlainToolCallHasDenyAndApproveOnTheNotificationAndOnItsLockScreenVersion() {
        val notification = post("ahp-session:/a", target)

        assertEquals(listOf("Deny", "Approve"), titles(notification))
        assertEquals("the lock screen version has the buttons too", listOf("Deny", "Approve"), titles(checkNotNull(notification.publicVersion)))
    }

    @Test
    fun noButtonsWhileTheAppIsNotAllowedToActForTheUser() {
        val notification = post("ahp-session:/a", target, canDecide = false)

        assertEquals(emptyList<String>(), titles(notification))
        assertEquals(emptyList<String>(), titles(checkNotNull(notification.publicVersion)))
    }

    @Test
    fun noButtonsWhenThereIsNoSingleToolCallToDecide() {
        val notification = post("ahp-session:/a", approval = null)

        assertEquals(emptyList<String>(), titles(notification))
        assertEquals(emptyList<String>(), titles(checkNotNull(notification.publicVersion)))
    }

    @Test
    fun theLockScreenVersionStillHidesWhatTheSessionIsWaitingFor() {
        val lockScreen = checkNotNull(post("ahp-session:/a", target).publicVersion)

        val everything = lockScreen.extras.keySet().mapNotNull { lockScreen.extras.get(it)?.toString() }.joinToString("\n")
        assertFalse(everything.contains("Run the tests"))
        assertFalse(everything.contains("Fix the build"))
    }

    @Test
    fun theButtonsOfOneNotificationNeverReachAnothersToolCall() {
        val first = post("ahp-session:/a", target)
        val otherSession = post("ahp-session:/b", target.copy(chat = "ahp-chat://default/y"))
        val sameSessionOtherCall = post("ahp-session:/a2", target.copy(toolCallId = "call-2"))

        for (index in 0..1) {
            val buttons = listOf(first, otherSession, sameSessionOtherCall).map { it.actions[index].actionIntent }
            assertEquals("each button is its own", 3, buttons.toSet().size)
        }
    }

    @Test
    fun theHostsOwnOptionsAreTheButtonsInItsWordsWithTheRefusalFirst() {
        val notification = post("ahp-session:/a", withOptions)

        assertEquals(listOf("Skip", "Allow once", "Allow in this session"), titles(notification))
        assertEquals("the lock screen version has them too", listOf("Skip", "Allow once", "Allow in this session"), titles(checkNotNull(notification.publicVersion)))
    }

    @Test
    fun everyOptionButtonIsItsOwnAndNeverAnotherNotificationsButton() {
        val first = post("ahp-session:/a", withOptions)
        val otherCall = post("ahp-session:/b", withOptions.copy(toolCallId = "call-2"))

        val buttons = (first.actions.toList() + otherCall.actions.toList()).map { it.actionIntent }
        assertEquals("six buttons, six different intents", 6, buttons.toSet().size)
    }

    @Test
    fun noOptionButtonsWhileTheAppIsNotAllowedToActForTheUser() {
        assertEquals(emptyList<String>(), titles(post("ahp-session:/a", withOptions, canDecide = false)))
    }

    @Test
    fun postingTheSameCallAgainKeepsTheSameButtonsRatherThanAddingMore() {
        val before = post("ahp-session:/a", target)
        val after = post("ahp-session:/a", target)

        assertEquals(before.actions[0].actionIntent, after.actions[0].actionIntent)
        assertEquals(before.actions[1].actionIntent, after.actions[1].actionIntent)
        assertNotEquals("Deny and Approve are different buttons", before.actions[0].actionIntent, before.actions[1].actionIntent)
        assertEquals(1, manager.activeNotifications.count { it.tag == tag("a") })
    }

    @Test
    fun nothingOutsideTheAppCanStartTheApproveScreenOrPressDeny() {
        val approve = context.packageManager.getActivityInfo(ComponentName(context, ApproveActivity::class.java), 0)
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, DecisionReceiver::class.java), 0)

        assertFalse("another app must not be able to start the approval screen", approve.exported)
        assertFalse("another app must not be able to send Deny", receiver.exported)
        val answerReceiver = context.packageManager.getReceiverInfo(ComponentName(context, AnswerReceiver::class.java), 0)
        assertFalse("another app must not be able to send an answer", answerReceiver.exported)
        assertTrue("it stays out of the recent apps list", approve.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertEquals("one at a time, never stacked", ActivityInfo.LAUNCH_SINGLE_INSTANCE, approve.launchMode)
    }

    @Test
    fun theApproveScreenEndsAtOnceWhenItWasNotGivenACall() {
        val intent = Intent(context, ApproveActivity::class.java)

        ActivityScenario.launch<ApproveActivity>(intent).use { scenario ->
            waitUntilDestroyed(scenario)
        }
    }

    @Test
    fun theApproveScreenEndsByItselfWhenThereIsNoConnectionToDecideOn() {
        val intent = Intent(context, ApproveActivity::class.java)
            .putExtra(EXTRA_CHAT_URI, target.chat)
            .putExtra(EXTRA_TOOL_CALL_ID, target.toolCallId)

        // Nothing is signed in or connected in a fresh test run, so the decision is refused and the screen goes.
        ActivityScenario.launch<ApproveActivity>(intent).use { scenario ->
            waitUntilDestroyed(scenario)
        }
    }

    private fun waitUntilDestroyed(scenario: ActivityScenario<ApproveActivity>) {
        repeat(100) {
            if (scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED) return
            Thread.sleep(100)
        }
        error("the approval screen did not end by itself")
    }

    private val questionTarget = QuestionTarget("ahp-chat:/c1", "req-1")

    private fun postQuestion(session: String, question: QuestionTarget? = questionTarget, canDecide: Boolean = true): Notification {
        val unique = tag(session)
        NotificationInputNeededSink(context, canDecide = { canDecide }).post(InputNeeded(unique, "Color preference", "Which season do you prefer?", question = question))
        return shown(unique).notification
    }

    @Test
    fun aSingleTextQuestionHasAnAnswerFieldOnlyWhenTheAppMayActForTheUser() {
        val allowed = postQuestion("q-allowed", canDecide = true)
        assertEquals(listOf("Answer"), titles(allowed))
        assertEquals(listOf("Answer"), titles(checkNotNull(allowed.publicVersion)))
        val remoteInput = allowed.actions.single().remoteInputs?.singleOrNull()
        assertEquals(KEY_TEXT_REPLY, remoteInput?.resultKey)

        val readOnly = postQuestion("q-readonly", canDecide = false)
        assertEquals(emptyList<String>(), titles(readOnly))

        val sink = NotificationInputNeededSink(context, canDecide = { true })
        sink.postReplyStatus(tag("q-allowed"), "Sent")
        val updated = checkNotNull(manager.activeNotifications.firstOrNull { it.tag == tag("q-allowed") }).notification
        assertEquals("Sent", updated.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(emptyList<String>(), titles(updated))
    }
}
