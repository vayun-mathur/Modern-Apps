package com.vayunmathur.email.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.log.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Wakes snoozed messages whose time has arrived: clears their `snoozedUntil`
 * and marks them unread so they resurface at the top of the inbox. Scheduled
 * once per snooze via [scheduleNext]; each run clears all currently-due rows.
 */
class SnoozeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    // WorkManager contract (everysync pattern): return a Result, never throw.
    // Cancellation rethrows; transient DB I/O retries (this one-shot would
    // otherwise lose the wake-up); anything else fails rather than retrying
    // a corrupt row set forever.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        return try {
            val dao = EmailRepository.get(applicationContext).getDatabase().messageDao()
            dao.wakeDueSnoozed(System.currentTimeMillis())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.error(TAG, "snooze wake-up failed; will retry", e)
            Result.retry()
        } catch (e: Exception) {
            Log.error(TAG, "snooze wake-up failed", e)
            Result.failure()
        }
    }

    /** Schedules the one-shot worker that un-snoozes messages at the requested time. */
    companion object {
        private const val TAG = "SnoozeWorker"
        fun scheduleNext(context: Context, atMillis: Long) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
            val request = OneTimeWorkRequestBuilder<SnoozeWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
