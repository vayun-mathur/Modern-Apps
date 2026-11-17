package com.vayunmathur.contacts.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.util.DataStoreUtils
import java.util.concurrent.TimeUnit

/** Periodic worker that re-syncs contact events to the calendar while calendar sync is enabled. */
class CalendarWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        val isEnabled = dataStore.getBoolean("calendar_sync_enabled", false)
        
        if (isEnabled) {
            CalendarSyncHelper.syncAll(applicationContext)
        }
        
        return Result.success()
    }

    /** Unique work name and scheduling entry points for [CalendarWorker]. */
    companion object {
        private const val WORK_NAME = "CalendarSyncWorker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<CalendarWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
        
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
