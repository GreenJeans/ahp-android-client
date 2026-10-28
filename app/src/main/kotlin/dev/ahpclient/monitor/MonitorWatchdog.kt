package dev.ahpclient.monitor

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ahpclient.AhpApp
import dev.ahpclient.app.WatchdogDecision
import java.util.concurrent.TimeUnit

/**
 * Schedules the check that tells the person when monitoring has stopped, for example because Android killed the process. It runs
 * about every 15 minutes, which is the shortest period WorkManager allows, and needs no network. It never starts the foreground
 * service: Android forbids that from the background, so all it can do is say so and let a tap open the app.
 */
object MonitorWatchdog {
    internal const val WORK_NAME = "monitor-watchdog"
    private const val PERIOD_MINUTES = 15L

    /**
     * Keeps the job that is already scheduled, so opening the app does not push the next check back. A new job waits a whole period
     * before its first check: turning monitoring on is not the moment to say it has stopped.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<MonitorWatchdogWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setInitialDelay(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

/**
 * One check: with monitoring on, no live session in this process and the app out of sight, post the warning. In a process the system
 * started just for this nothing has restored the sign-in yet, which counts as no live session. The decision is [WatchdogDecision].
 */
class MonitorWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? AhpApp ?: applicationContext.applicationContext as AhpApp).container
        if (container.monitoringStopped()) NotificationMonitorProblemSink(applicationContext).post(WatchdogDecision.STOPPED_TEXT)
        return Result.success()
    }
}
