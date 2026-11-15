package com.vayunmathur.passwords.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.passwords.data.PasswordRepository

/** Drives the kdbx sync from WorkManager: periodically, on app open, and after local edits. */
class KdbxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The Room passphrase is only mirrored to the non-auth key once the user has
        // unlocked the app; before that there is no database to sync.
        if (!DatabaseHelper(applicationContext).isKeyGenerated()) return Result.success()

        return try {
            val repository = PasswordRepository.get(applicationContext)
            when (val result = runKdbxSync(applicationContext, repository)) {
                is KdbxSyncResult.Success, KdbxSyncResult.NotConfigured -> Result.success()
                is KdbxSyncResult.Quarantined -> {
                    // Ambiguous foreign absence: hold for explicit user review.
                    // Failure (not retry) so WorkManager does not hammer the
                    // file; the user resolves it from the settings screen.
                    Log.status(TAG, "KDBX quarantined: ${result.count} ids ambiguous")
                    Result.failure()
                }
                KdbxSyncResult.WrongPassword, KdbxSyncResult.VerifyFailed -> Result.failure()
                KdbxSyncResult.FileMissing, is KdbxSyncResult.Error -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /** Shared constants and helpers for KdbxSyncWorker. */
    companion object {
        private const val TAG = "KdbxSyncWorker"
    }
}
