package dev.ahpclient.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import dev.ahpclient.AhpApp
import dev.ahpclient.app.WatchdogDecision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android side of the "monitoring stopped" check: the worker's post, and the schedule. The decision itself is
 * `WatchdogDecision`, tested on the JVM. A test process has no live session, which is the case the check exists for.
 */
@RunWith(AndroidJUnit4::class)
class MonitorWatchdogInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as AhpApp).container
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** What the person had Monitoring set to on this device, put back afterwards: these tests run against the installed app's own data. */
    private var monitoringBefore = false

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        monitoringBefore = runBlocking { container.monitor.enabled.first() }
        manager.cancel(WARNING_ID)
    }

    @After
    fun putMonitoringBack() {
        runBlocking { container.monitor.setEnabled(monitoringBefore) }
        if (monitoringBefore) MonitorWatchdog.schedule(context) else MonitorWatchdog.cancel(context)
        manager.cancel(WARNING_ID)
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<MonitorWatchdogWorker>(context).build().doWork() }

    private fun warning() = manager.activeNotifications.firstOrNull { it.id == WARNING_ID }

    @Test
    fun withMonitoringOnAndNoLiveSessionTheCheckPostsOneWarningThatOpensTheApp() {
        runBlocking { container.monitor.setEnabled(true) }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(ListenableWorker.Result.success(), runWorker())

        val shown = checkNotNull(warning()) { "the warning was never posted" }.notification
        assertEquals(WatchdogDecision.STOPPED_TEXT, shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(MonitorChannels.PROBLEM, shown.channelId)
        assertNotNull(shown.contentIntent)
        assertEquals("a second check replaces the first", 1, manager.activeNotifications.count { it.id == WARNING_ID })
    }

    @Test
    fun withMonitoringOffTheCheckPostsNothing() {
        runBlocking { container.monitor.setEnabled(false) }

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertNull(warning())
    }

    @Test
    fun withTheAppInFrontTheCheckPostsNothing() {
        runBlocking { container.monitor.setEnabled(true) }
        container.inForeground = true
        try {
            assertEquals(ListenableWorker.Result.success(), runWorker())
        } finally {
            container.inForeground = false
        }

        assertNull(warning())
    }

    @Test
    fun theCheckIsScheduledOncePeriodicallyAndCanBeCancelled() {
        val work = WorkManager.getInstance(context)

        MonitorWatchdog.schedule(context)
        MonitorWatchdog.schedule(context)
        val scheduled = work.getWorkInfosForUniqueWork(MonitorWatchdog.WORK_NAME).get()
        assertEquals(1, scheduled.size)
        assertEquals(WorkInfo.State.ENQUEUED, scheduled.single().state)

        MonitorWatchdog.cancel(context)
        assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfosForUniqueWork(MonitorWatchdog.WORK_NAME).get().single().state)
    }

    private companion object {
        /** The id the problem notification is posted under. */
        const val WARNING_ID = NotificationMonitorProblemSink.NOTIFICATION_ID
    }
}
