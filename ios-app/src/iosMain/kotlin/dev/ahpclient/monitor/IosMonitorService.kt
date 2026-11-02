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
    val CATEGORY_APPROVAL: String = "AHP_INPUT_APPROVAL"
    val CATEGORY_QUESTION: String = "AHP_INPUT_QUESTION"
    val ACTION_APPROVE: String = "AHP_ACTION_APPROVE"
    val ACTION_DENY: String = "AHP_ACTION_DENY"
    val ACTION_REPLY: String = "AHP_ACTION_ANSWER"

    val KEY_SESSION_URI: String = "sessionUri"
    val KEY_CHAT_URI: String = "chatUri"
    val KEY_ITEM_ID: String = "itemId"
    val KEY_APPROVE_OPTION_ID: String = "optionId"

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
            identifier = ACTION_REPLY,
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

    override fun post(request: InputNeeded) {
        val content = UNMutableNotificationContent().apply {
            setTitle(request.title)
            setBody(request.detail ?: "Needs your input")
            setSound(UNNotificationSound.defaultSound())
        }
        val userInfo = LinkedHashMap<Any?, Any?>()
        userInfo[IosNotificationCategories.KEY_SESSION_URI] = request.resource
        if (canDecide()) {
            request.approval?.let { approval ->
                content.setCategoryIdentifier(IosNotificationCategories.CATEGORY_APPROVAL)
                userInfo[IosNotificationCategories.KEY_CHAT_URI] = approval.chat
                userInfo[IosNotificationCategories.KEY_ITEM_ID] = approval.toolCallId
                approval.options.firstOrNull { it.approves }?.id?.let { optionId ->
                    userInfo[IosNotificationCategories.KEY_APPROVE_OPTION_ID] = optionId
                }
            }
            request.question?.let { question ->
                content.setCategoryIdentifier(IosNotificationCategories.CATEGORY_QUESTION)
                userInfo[IosNotificationCategories.KEY_CHAT_URI] = question.chat
                userInfo[IosNotificationCategories.KEY_ITEM_ID] = question.requestId
            }
        }
        content.setUserInfo(userInfo)
        val req = UNNotificationRequest.requestWithIdentifier(
            identifier = inputId(request.resource),
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(req, withCompletionHandler = null)
    }

    override fun clear(resource: String) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(inputId(resource)))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(inputId(resource)))
    }

    private fun inputId(resource: String): String = "ahp.input.$resource"
}

class IosNotificationFinishedSink : FinishedSink {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override fun post(finished: FinishedSession) {
        val content = UNMutableNotificationContent().apply {
            setTitle(finished.title)
            setBody(FinishedText.detail(finished))
            setSound(UNNotificationSound.defaultSound())
            setUserInfo(mapOf<Any?, Any?>(IosNotificationCategories.KEY_SESSION_URI to finished.resource))
        }
        val req = UNNotificationRequest.requestWithIdentifier(
            identifier = finishedId(finished.resource),
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(req, withCompletionHandler = null)
    }

    override fun clear(resource: String) {
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
