package com.vayunmathur.email.data

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.email.platform.BootReceiver

/**
 * Retry worker that attempts to start [ImapIdleService] when network is available.
 * Uses expedited work with OUT_OF_QUOTA so it can try even if quota exhausted.
 * Scheduled from [BootReceiver] on S+ boot as fallback, and from [com.vayunmathur.email.platform.AppLifecycleTracker]
 * foreground path if direct start fails.
 */
class ImapIdleRetryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val started = ImapIdleService.start(applicationContext)
            if (started) {
                Log.debug(TAG, "ImapIdleRetryWorker: IDLE started")
                // Clear pending flag on success
                applicationContext.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
                    .edit { remove(BootReceiver.KEY_PENDING) }
                Result.success()
            } else {
                Log.status(TAG, "ImapIdleRetryWorker: start returned false, retrying")
                Result.retry()
            }
        } catch (t: SecurityException) {
            Log.status(TAG, "ImapIdleRetryWorker failed: ${t.message}", t)
            Result.retry()
        } catch (t: IllegalStateException) {
            Log.status(TAG, "ImapIdleRetryWorker failed: ${t.message}", t)
            Result.retry()
        }
    }

    /** Holds WorkManager names and logging tags for the IMAP IDLE retry worker. */
    companion object {
        private const val TAG = "ImapIdleRetry"
        private const val WORK_NAME = "ImapIdleRetryWorker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<ImapIdleRetryWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
