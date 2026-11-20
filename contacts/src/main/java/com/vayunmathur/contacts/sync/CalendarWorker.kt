package com.vayunmathur.contacts.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DataStoreUtils
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Periodic worker that re-syncs contact events to the calendar while calendar sync is enabled. */
class CalendarWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // WorkManager contract (everysync pattern): return a Result, never throw.
    // Cancellation rethrows; transient provider/DataStore I/O retries with
    // WorkManager backoff; a revoked permission or bad data fails this run —
    // the hourly period runs again with whatever state exists then.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        return try {
            val dataStore = DataStoreUtils.getInstance(applicationContext)
            val isEnabled = dataStore.getBoolean("calendar_sync_enabled", false)

            if (isEnabled) {
                CalendarSyncHelper.syncAll(applicationContext)
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.error(TAG, "calendar sync failed; will retry", e)
            Result.retry()
        } catch (e: android.database.SQLException) {
            Log.error(TAG, "calendar sync failed; will retry", e)
            Result.retry()
        } catch (e: Exception) {
            Log.error(TAG, "calendar sync failed", e)
            Result.failure()
        }
    }

    /** Unique work name and scheduling entry points for [CalendarWorker]. */
    companion object {
        private const val TAG = "CalendarWorker"
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
