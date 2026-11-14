package com.vayunmathur.everysync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.log.Log
import java.util.concurrent.TimeUnit

/**
 * Takes the daily encrypted snapshot of every vault ([SnapshotStore]) and
 * prunes entries older than the 30-day retention. Runs once a day, survives
 * reboots via the same WorkManager persistence as the sync schedule (plus
 * [com.vayunmathur.everysync.platform.BootReceiver] re-registration).
 */
class SnapshotWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val taken = SnapshotStore.snapshotAll(applicationContext)
            Log.status(TAG, "daily snapshots: $taken taken")
            Result.success()
        } catch (expected: Exception) {
            Log.error(TAG, "daily snapshots failed", expected)
            Result.retry()
        }
    }

    /** Holds WorkManager names and logging constants for daily snapshots. */
    companion object {
        private const val TAG = "SnapshotWorker"
        private const val UNIQUE_WORK = "EverySyncSnapshots"

        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<SnapshotWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
