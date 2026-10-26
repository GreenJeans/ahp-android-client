package dev.ahpclient.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahpclient.MainActivity
import dev.ahpclient.state.FinishedSession
import dev.ahpclient.state.InputNeeded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Android side of background monitoring that cannot be checked on the JVM: channels, notifications and the manifest. */
@RunWith(AndroidJUnit4::class)
class MonitorInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        manager.cancelAll()
    }

    private fun waitForNotification(tag: String): StatusBarNotification? {
        repeat(30) {
            manager.activeNotifications.firstOrNull { it.tag == tag }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    @Test
    fun aNeedsInputNotificationCarriesTheTitleButHidesItOnALockedScreen() {
        NotificationInputNeededSink(context).post(InputNeeded("ahp-session:/abc", "Fix the build"))

        val shown = checkNotNull(waitForNotification("ahp-session:/abc")) { "the notification was never posted" }.notification
        assertEquals("Needs your input", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Fix the build", shown.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertEquals(MonitorChannels.INPUT_NEEDED, shown.channelId)
        val lockScreen = checkNotNull(shown.publicVersion) { "no lock-screen version" }
        assertEquals("A session needs your input", lockScreen.extras.getString(Notification.EXTRA_TITLE))
        assertFalse("the title must not reach the lock screen", lockScreen.extras.keySet().any { lockScreen.extras.get(it)?.toString()?.contains("Fix the build") == true })
        assertNotNull(shown.contentIntent)
    }

    @Test
    fun theAlertShowsWhatTheSessionWaitsForWithTheTitleBesideItAndHidesBothOnALockedScreen() {
        NotificationInputNeededSink(context).post(InputNeeded("ahp-session:/abc", "Color preference", "Which season do you prefer?"))

        val shown = checkNotNull(waitForNotification("ahp-session:/abc")) { "the notification was never posted" }.notification
        assertEquals("Needs your input", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Which season do you prefer?", shown.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Color preference", shown.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertEquals("Which season do you prefer?", shown.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        val lockScreen = checkNotNull(shown.publicVersion) { "no lock-screen version" }
        assertEquals("A session needs your input", lockScreen.extras.getString(Notification.EXTRA_TITLE))
        val everythingOnTheLockScreen = lockScreen.extras.keySet().mapNotNull { lockScreen.extras.get(it)?.toString() }.joinToString("\n")
        assertFalse("the question must not reach the lock screen", everythingOnTheLockScreen.contains("season"))
        assertFalse("the title must not reach the lock screen", everythingOnTheLockScreen.contains("Color preference"))
    }

    @Test
    fun postingTheSameSessionAgainReplacesItAndClearingRemovesIt() {
        val sink = NotificationInputNeededSink(context)
        sink.post(InputNeeded("ahp-session:/abc", "First"))
        sink.post(InputNeeded("ahp-session:/abc", "Second"))
        waitForNotification("ahp-session:/abc")
        Thread.sleep(300)

        val now = manager.activeNotifications.filter { it.tag == "ahp-session:/abc" }
        assertEquals(1, now.size)
        assertEquals("Second", now.single().notification.extras.getString(Notification.EXTRA_TEXT))

        sink.clear("ahp-session:/abc")
        Thread.sleep(300)
        assertNull(manager.activeNotifications.firstOrNull { it.tag == "ahp-session:/abc" })
    }

    @Test
    fun theChannelsHaveTheRightImportance() {
        MonitorChannels.ensure(context)

        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(MonitorChannels.MONITOR).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(MonitorChannels.INPUT_NEEDED).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(MonitorChannels.FINISHED).importance)
    }

    @Test
    fun aFinishedNotificationNamesTheSessionAndHowItEndedAndHidesBothOnALockedScreen() {
        NotificationFinishedSink(context).post(FinishedSession("ahp-session:/abc", "Fix the build", failed = false, tookMs = 496_000))

        val shown = checkNotNull(waitForNotification("ahp-session:/abc")) { "the notification was never posted" }.notification
        assertEquals("Fix the build", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Done · 8m 16s", shown.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(MonitorChannels.FINISHED, shown.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertNotNull(shown.contentIntent)
        assertTrue("tapping it clears it", shown.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val lockScreen = checkNotNull(shown.publicVersion) { "no lock-screen version" }
        assertEquals("A session finished", lockScreen.extras.getString(Notification.EXTRA_TITLE))
        val everythingOnTheLockScreen = lockScreen.extras.keySet().mapNotNull { lockScreen.extras.get(it)?.toString() }.joinToString("\n")
        assertFalse("the title must not reach the lock screen", everythingOnTheLockScreen.contains("Fix the build"))
        assertFalse("the result must not reach the lock screen", everythingOnTheLockScreen.contains("8m"))
    }

    @Test
    fun aFailedSessionSaysFailedAndAnUnknownLengthIsLeftOut() {
        val sink = NotificationFinishedSink(context)
        sink.post(FinishedSession("ahp-session:/f", "Broke", failed = true, tookMs = 4_000))
        sink.post(FinishedSession("ahp-session:/n", "No length", failed = false, tookMs = null))

        assertEquals("Failed · 4s", checkNotNull(waitForNotification("ahp-session:/f")).notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Done", checkNotNull(waitForNotification("ahp-session:/n")).notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun aVeryLongTitleIsCut() {
        NotificationFinishedSink(context).post(FinishedSession("ahp-session:/long", "x".repeat(500), failed = false, tookMs = null))

        val title = checkNotNull(waitForNotification("ahp-session:/long")).notification.extras.getString(Notification.EXTRA_TITLE)!!
        assertEquals(120, title.length)
    }

    @Test
    fun postingAFinishedAlertAgainReplacesItAndClearingRemovesOnlyThat() {
        val finished = NotificationFinishedSink(context)
        NotificationInputNeededSink(context).post(InputNeeded("ahp-session:/abc", "Asking"))
        finished.post(FinishedSession("ahp-session:/abc", "First", failed = false, tookMs = null))
        finished.post(FinishedSession("ahp-session:/abc", "Second", failed = false, tookMs = null))
        waitForNotification("ahp-session:/abc")
        Thread.sleep(300)

        val now = manager.activeNotifications.filter { it.tag == "ahp-session:/abc" }
        assertEquals("the needs-input and the finished alert of one session live side by side", 2, now.size)
        assertEquals(setOf("Needs your input", "Second"), now.map { it.notification.extras.getString(Notification.EXTRA_TITLE) }.toSet())

        finished.clear("ahp-session:/abc")
        Thread.sleep(300)
        val left = manager.activeNotifications.filter { it.tag == "ahp-session:/abc" }
        assertEquals(1, left.size)
        assertEquals(MonitorChannels.INPUT_NEEDED, left.single().notification.channelId)
    }

    @Test
    fun theManifestDeclaresTheServiceAsDataSyncWithItsPermissions() {
        val service = context.packageManager.getServiceInfo(ComponentName(context, MonitorService::class.java), 0)
        // Foreground-service types were introduced in API 29; older systems do not record one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
        }
        assertFalse("nothing outside the app may start it", service.exported)

        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(
            requested.containsAll(
                listOf(
                    Manifest.permission.INTERNET,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
            ),
        )
    }

    @Test
    fun startingTheServiceWithNothingToMonitorDoesNotCrashAndLeavesNothingBehind() {
        AndroidMonitorService(context).start()
        Thread.sleep(3_000)

        assertNull("a service with nothing to monitor must not leave its notification", manager.activeNotifications.firstOrNull { it.id == 100 })
    }
}
