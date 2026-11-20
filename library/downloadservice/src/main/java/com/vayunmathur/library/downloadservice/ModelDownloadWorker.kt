package com.vayunmathur.library.downloadservice

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.ensureNotificationChannel
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Runs the model download outside any composition.
 *
 * The download used to run in a `LaunchedEffect` on the gating screen, so it was cancelled by a
 * rotation, a back press or the app being backgrounded, and a partial transfer of a multi-hundred-MB
 * model only resumed if the user happened to reopen that exact screen. Here it survives all of
 * those, and a failed run is retried by WorkManager with exponential backoff instead of being
 * swallowed.
 *
 * This is a foreground worker: [doWork] calls `setForeground` with a LOW progress
 * notification so a large bundle can finish in one pass instead of being stopped
 * after roughly ten minutes of background execution. The work stays resumable
 * regardless — partial bytes live in `<fileName>.part` and a later run continues
 * with a `Range` request — so a foreground promotion failure (or the platform
 * reclaiming the service) degrades to the old multi-run behaviour, not data loss.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    // WorkManager contract (everysync pattern): return a Result, never throw.
    // Cancellation rethrows (runDownloadsCore keeps the `.part` for resume);
    // a thrown transient I/O error takes the same capped-retry path as a
    // `false` return below, and anything else fails at once.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val specs = readSpecs(inputData)
        if (specs.isEmpty()) return Result.failure()

        // Foreground promotion (see class doc): a failure here must not fail the
        // download — the resumable background path still completes it across runs.
        try {
            setForeground(createForegroundInfo(specs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.status(TAG, "could not promote to foreground; continuing in background", e)
        }

        val targetDir = inputData.getString(KEY_TARGET_DIR)?.let(::File)
        val ds = DataStoreUtils.getInstance(applicationContext)
        return try {
            when {
                runDownloadsCore(applicationContext, ds, specs, targetDir) -> Result.success()
                // Keep retrying transient problems (flaky network, mirror hiccup) but eventually stop so
                // the screen can offer a retry rather than backing off forever.
                runAttemptCount + 1 >= MAX_RUN_ATTEMPTS -> Result.failure()
                else -> Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.error(TAG, "model download failed; will retry", e)
            if (runAttemptCount + 1 >= MAX_RUN_ATTEMPTS) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Log.error(TAG, "model download failed", e)
            Result.failure()
        }
    }

    /**
     * LOW, indeterminate progress notification for the foreground promotion.
     *
     * Kept local to downloadservice: this library has no host `MainActivity` to
     * target, so the tap intent is the hosting app's launcher intent
     * (`getLaunchIntentForPackage`), falling back to no content intent when the
     * host has no launcher entry. Indeterminate because [runDownloadsCore]
     * reports no per-byte progress to the worker.
     */
    private fun createForegroundInfo(specs: List<DownloadSpec>): ForegroundInfo {
        applicationContext.ensureNotificationChannel(
            id = CHANNEL_ID,
            name = applicationContext.getString(R.string.downloading),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = applicationContext.getString(
                R.string.downloading_required_components_for_this
            ),
        )
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.downloading))
            .setContentText(
                specs.firstOrNull()?.description
                    ?: applicationContext.getString(R.string.downloading_required_components_for_this)
            )
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (launchIntent != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    NOTIFICATION_ID,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, builder.build())
        }
        return foregroundInfo
    }

    /** WorkManager input keys, enqueue entry points and spec decoding for [ModelDownloadWorker]. */
    companion object {
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 9101
        private const val TAG = "ModelDownloadWorker"
        private const val KEY_URLS = "urls"
        private const val KEY_FILE_NAMES = "fileNames"
        private const val KEY_DESCRIPTIONS = "descriptions"
        private const val KEY_HASHES = "hashes"
        private const val KEY_SIZES = "sizes"
        private const val KEY_TARGET_DIR = "targetDir"
        private const val KEY_UNMETERED = "unmetered"

        /** Absent SHA-256, encoded as an empty string because [Data] holds no nulls. */
        private const val NO_HASH = ""

        /** Absent expected size, encoded as -1 because [Data] holds no nulls. */
        private const val NO_SIZE = -1L

        private const val MAX_RUN_ATTEMPTS = 6
        private const val BACKOFF_SECONDS = 30L
        private const val SINGLETON_LIST_SIZE = 1

        /**
         * Stable unique-work name for [specs], so re-entering the screen observes the run already in
         * flight instead of starting a second one.
         */
        internal fun uniqueName(specs: List<DownloadSpec>): String {
            val key = specs.joinToString(SPEC_SEPARATOR) { it.fileName }.hashCode()
            return "model_download_${key.toUInt().toString(HEX_RADIX)}"
        }

        private const val SPEC_SEPARATOR = "\u0000"
        private const val HEX_RADIX = 16

        /** Enqueues the download, keeping any run already in progress for the same [specs]. */
        internal fun enqueue(context: Context, specs: List<DownloadSpec>) =
            enqueue(context, specs, ExistingWorkPolicy.KEEP)

        /** Restarts the download after a failure, replacing the failed run. */
        internal fun retry(context: Context, specs: List<DownloadSpec>) =
            enqueue(context, specs, ExistingWorkPolicy.REPLACE)

        /**
         * Enqueues a download that writes outside the app's external files dir, and optionally
         * only over an unmetered network.
         *
         * Used by networklocation, whose multi-gigabyte offline databases go to device-protected
         * storage so the always-on provider can read them before first unlock, and which must not
         * pull gigabytes over cellular.
         */
        fun enqueueTo(
            context: Context,
            models: List<ModelDownloadItem>,
            targetDir: File,
            requireUnmetered: Boolean = false,
            replaceExisting: Boolean = false,
        ) = enqueue(
            context = context,
            specs = models.map {
                DownloadSpec(
                    fileName = it.fileName,
                    description = it.description,
                    url = it.url,
                    sha256 = it.sha256,
                    expectedBytes = it.expectedBytes,
                )
            },
            policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            targetDir = targetDir,
            requireUnmetered = requireUnmetered,
        )

        private fun enqueue(
            context: Context,
            specs: List<DownloadSpec>,
            policy: ExistingWorkPolicy,
            targetDir: File? = null,
            requireUnmetered: Boolean = false,
        ) {
            val data = Data.Builder()
                .putStringArray(KEY_URLS, specs.map { it.url }.toTypedArray())
                .putStringArray(KEY_FILE_NAMES, specs.map { it.fileName }.toTypedArray())
                .putStringArray(KEY_DESCRIPTIONS, specs.map { it.description }.toTypedArray())
                .putStringArray(KEY_HASHES, specs.map { it.sha256 ?: NO_HASH }.toTypedArray())
                .putLongArray(KEY_SIZES, specs.map { it.expectedBytes ?: NO_SIZE }.toLongArray())
                .apply { targetDir?.let { putString(KEY_TARGET_DIR, it.absolutePath) } }
                .putBoolean(KEY_UNMETERED, requireUnmetered)
                .build()

            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(specs), policy, request)
        }

        private fun readSpecs(data: Data): List<DownloadSpec> {
            val urls = data.getStringArray(KEY_URLS) ?: return emptyList()
            val fileNames = data.getStringArray(KEY_FILE_NAMES) ?: return emptyList()
            val descriptions = data.getStringArray(KEY_DESCRIPTIONS) ?: return emptyList()
            val hashes = data.getStringArray(KEY_HASHES) ?: return emptyList()
            // Old enqueues predate sizes: default every entry to unknown.
            val sizes = data.getLongArray(KEY_SIZES)
            val sizesMismatch = fileNames.size != urls.size ||
                descriptions.size != urls.size ||
                hashes.size != urls.size ||
                (sizes != null && sizes.size != urls.size)
            if (sizesMismatch) {
                return emptyList()
            }
            return urls.indices.map { i ->
                DownloadSpec(
                    fileName = fileNames[i],
                    description = descriptions[i],
                    url = urls[i],
                    sha256 = hashes[i].takeIf { it != NO_HASH },
                    expectedBytes = sizes?.get(i)?.takeIf { it != NO_SIZE },
                )
            }
        }
    }
}
