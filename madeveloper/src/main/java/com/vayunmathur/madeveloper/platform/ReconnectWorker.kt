package com.vayunmathur.madeveloper.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic wake-up that nudges the push socket back open after the process
 * was reclaimed; the live connection itself is held by the view-model scope
 * while the app is running, like findfamily's heartbeat reconnect.
 */
class ReconnectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()

    /** Schedules the periodic reconnect nudge once. */
    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReconnectWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "dev-push-reconnect", ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
