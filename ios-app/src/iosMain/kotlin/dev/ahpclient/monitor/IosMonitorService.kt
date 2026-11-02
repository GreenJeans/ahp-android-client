@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ahpclient.monitor

import dev.ahpclient.app.FinishedSink
import dev.ahpclient.app.InputNeededSink
import dev.ahpclient.app.MonitorProblemSink
import dev.ahpclient.app.MonitorService
import dev.ahpclient.state.FinishedSession
import dev.ahpclient.state.FinishedText
import dev.ahpclient.state.InputNeeded
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionAuthenticationRequired
import platform.UserNotifications.UNNotificationActionOptionDestructive
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTextInputNotificationAction
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS implementation of [MonitorService] using `UIApplication.beginBackgroundTaskWithName` to keep the WebSocket
 * alive during background transitions while monitoring is enabled.
 */
class IosMonitorService : MonitorService {
    private var backgroundTaskId = UIBackgroundTaskInvalid
    private var active = false

    override fun start(): Boolean {
        active = true
        return true
    }

    override fun stop() {
        active = false
        endBackgroundTask()
    }

    fun onEnterBackground() {
        if (!active) return
        if (backgroundTaskId != UIBackgroundTaskInvalid) return
        val app = UIApplication.sharedApplication
        backgroundTaskId = app.beginBackgroundTaskWithName("AhpSessionMonitor") {
            endBackgroundTask()
        }
    }

    fun onEnterForeground() {
        endBackgroundTask()
    }

    private fun endBackgroundTask() {
        val id = backgroundTaskId
        if (id != UIBackgroundTaskInvalid) {
            backgroundTaskId = UIBackgroundTaskInvalid
            UIApplication.sharedApplication.endBackgroundTask(id)
        }
    }
}

object IosNotificationCategories {
    const val CATEGORY_APPROVAL = "AHP_INPUT_APPROVAL"
    const val CATEGORY_QUESTION = "AHP_INPUT_QUESTION"
    const val ACTION_APPROVE = "AHP_ACTION_APPROVE"
    const val ACTION_DENY = "AHP_ACTION_DENY"
    const val ACTION_ANSWER = "AHP_ACTION_ANSWER"

    const val USER_INFO_SESSION_URI = "sessionUri"
    const val USER_INFO_CHAT_URI = "chatUri"
    const val USER_INFO_TOOL_CALL_ID = "toolCallId"
    const val USER_INFO_OPTION_ID = "optionId"
    const val USER_INFO_REQUEST_ID = "requestId"

    fun registerCategories() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val approveAction = UNNotificationAction.actionWithIdentifier(
            identifier = ACTION_APPROVE,
            title = "Approve",
            options = UNNotificationActionOptionAuthenticationRequired,
        )
        val denyAction = UNNotificationAction.actionWithIdentifier(
            identifier = ACTION_DENY,
            title = "Deny",
            options = UNNotificationActionOptionDestructive or UNNotificationActionOptionAuthenticationRequired,
        )
        val approvalCategory = UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY_APPROVAL,
            actions = listOf(approveAction, denyAction),
            intentIdentifiers = emptyList<String>(),
            options = 0u,
        )

        val answerAction = UNTextInputNotificationAction.actionWithIdentifier(
            identifier = ACTION_ANSWER,
            title = "Reply",
            options = UNNotificationActionOptionAuthenticationRequired,
            textInputButtonTitle = "Send",
            textInputPlaceholder = "Answer…",
        )
        val questionCategory = UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY_QUESTION,
            actions = listOf(answerAction),
            intentIdentifiers = emptyList<String>(),
            options = 0u,
        )

        center.setNotificationCategories(setOf(approvalCategory, questionCategory))
    }

    fun requestAuthorizationIfNeeded(onResult: (Boolean) -> Unit = {}) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        center.requestAuthorizationWithOptions(options) { granted, _ ->
            onResult(granted)
        }
    }
}

class IosNotificationInputNeededSink(
    private val canDecide: () -> Boolean = { false },
) : InputNeededSink {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override fun post(item: InputNeeded) {
        val content = UNMutableNotificationContent().apply {
            setTitle(item.title)
            setBody(item.body)
            setSound(UNNotificationSound.defaultSound())
        }
        val userInfo = LinkedHashMap<Any?, Any?>()
        userInfo[IosNotificationCategories.USER_INFO_SESSION_URI] = item.resource
        if (canDecide()) {
            item.approval?.let { approval ->
                content.setCategoryIdentifier(IosNotificationCategories.CATEGORY_APPROVAL)
                userInfo[IosNotificationCategories.USER_INFO_CHAT_URI] = approval.chatUri
                userInfo[IosNotificationCategories.USER_INFO_TOOL_CALL_ID] = approval.toolCallId
                approval.allowOptionId?.let { userInfo[IosNotificationCategories.USER_INFO_OPTION_ID] = it }
            }
            item.question?.let { question ->
                content.setCategoryIdentifier(IosNotificationCategories.CATEGORY_QUESTION)
                userInfo[IosNotificationCategories.USER_INFO_CHAT_URI] = question.chatUri
                userInfo[IosNotificationCategories.USER_INFO_REQUEST_ID] = question.requestId
            }
        }
        content.setUserInfo(userInfo)
        val req = UNNotificationRequest.requestWithIdentifier(
            identifier = inputId(item.resource),
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(req, withCompletionHandler = null)
    }

    override fun cancel(resource: String) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(inputId(resource)))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(inputId(resource)))
    }

    override fun cancelAll() {
        center.removeAllDeliveredNotifications()
    }

    private fun inputId(resource: String): String = "ahp.input.$resource"
}

class IosNotificationFinishedSink : FinishedSink {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override fun post(session: FinishedSession) {
        val text = FinishedText.of(session)
        val content = UNMutableNotificationContent().apply {
            setTitle(text.title)
            setBody(text.body)
            setSound(UNNotificationSound.defaultSound())
            setUserInfo(mapOf<Any?, Any?>(IosNotificationCategories.USER_INFO_SESSION_URI to session.resource))
        }
        val req = UNNotificationRequest.requestWithIdentifier(
            identifier = finishedId(session.resource),
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(req, withCompletionHandler = null)
    }

    override fun cancel(resource: String) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(finishedId(resource)))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(finishedId(resource)))
    }

    private fun finishedId(resource: String): String = "ahp.finished.$resource"
}

class IosNotificationMonitorProblemSink : MonitorProblemSink {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override fun post(text: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(text)
            setSound(UNNotificationSound.defaultSound())
        }
        val req = UNNotificationRequest.requestWithIdentifier(
            identifier = PROBLEM_ID,
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(req, withCompletionHandler = null)
    }

    override fun clear() {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(PROBLEM_ID))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(PROBLEM_ID))
    }

    private companion object {
        const val PROBLEM_ID = "ahp.monitor.problem"
    }
}
