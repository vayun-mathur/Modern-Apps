package com.vayunmathur.backup.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.backup.data.backend.BackendFactory
import com.vayunmathur.backup.data.backend.BackupRepository
import com.vayunmathur.backup.domain.crypto.Crypto
import com.vayunmathur.backup.platform.crypto.KeyManager
import com.vayunmathur.backup.data.BackupConfig
import com.vayunmathur.backup.data.files.FileBackupManager
import com.vayunmathur.library.work.startRepeatedTask
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.hours

/**
 * Periodic encrypted file/media backup, scheduled through :library:work (WorkManager).
 * No-ops unless a backend is configured, a master key exists, and file backup is
 * enabled in [BackupConfig].
 */
class FileBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = BackupConfig(applicationContext)
        val settings = config.settings.first()
        if (!settings.fileBackupEnabled) return Result.success()

        val backend = BackendFactory.create(applicationContext, settings) ?: return Result.success()
        val keyManager = KeyManager(applicationContext)
        if (!keyManager.hasMasterKey()) return Result.success()

        val repo = BackupRepository(backend, Crypto(keyManager.getMasterKey()))
        return try {
            FileBackupManager(applicationContext, repo).backupAll()
            config.setLastRun(System.currentTimeMillis())
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /** Work name and scheduling entry point for [FileBackupWorker]. */
    companion object {
        private const val WORK_NAME = "backup_file_backup"

        fun schedule(context: Context) {
            startRepeatedTask<FileBackupWorker>(context, WORK_NAME, 24.hours)
        }
    }
}
