package com.vayunmathur.everysync.sync

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.everysync.provider.SyncDirection

/**
 * WorkManager worker driving periodic + on-demand syncs. If an `account` input is
 * present only that account syncs; otherwise all accounts do.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val account = inputData.getString(KEY_ACCOUNT)
            if (account != null) {
                SyncEngine.syncAccount(applicationContext, account, SyncDirection.BOTH)
            } else {
                SyncEngine.syncAll(applicationContext, SyncDirection.BOTH)
            }
            Result.success()
        } catch (expected: Exception) {
            Log.error(TAG, "Sync failed", expected)
            Result.retry()
        }
    }

    /** Holds WorkManager input keys and logging constants for sync work. */
    companion object {
        const val KEY_ACCOUNT = "account"
        private const val TAG = "SyncWorker"
    }
}
