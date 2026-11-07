package com.vayunmathur.updater.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.vayunmathur.library.log.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.updater.R
import com.vayunmathur.updater.domain.OtaDownloadPlan
import com.vayunmathur.updater.domain.OtaPackageValidation
import com.vayunmathur.updater.notifications.UpdateNotifications
import com.vayunmathur.updater.platform.IdleReboot
import com.vayunmathur.updater.platform.OtaDownloader
import com.vayunmathur.updater.platform.OtaInstaller
import com.vayunmathur.updater.platform.RebootReceiver
import com.vayunmathur.updater.platform.SystemBuild
import com.vayunmathur.updater.platform.UpdaterPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "UpdateInstallService"

/** Held for the whole run: an FGS keeps the process alive, but not the CPU. */
private const val WAKE_LOCK_TAG = "updater:install"

/**
 * Runs one update from download to written slot.
 *
 * A foreground service rather than a worker because the transfer is one to two gigabytes and a
 * `WorkManager` job is stopped after roughly ten minutes — which on a slow connection is a
 * fraction of the download. The type is `specialUse`: `dataSync` is capped at six cumulative
 * hours per day on Android 15 and is refused outright when started from a boot receiver, and
 * this is neither a sync nor something that can be abandoned halfway.
 *
 * The service does the sequencing and the notification; the decisions live elsewhere. In
 * particular the verify-before-read ordering is [OtaInstaller]'s, so it cannot be got wrong by
 * rearranging this file.
 */
class UpdateInstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UpdateNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val build = intent?.getStringExtra(EXTRA_BUILD)
        val buildDate = intent?.getLongExtra(EXTRA_BUILD_DATE, 0L) ?: 0L
        if (build.isNullOrEmpty() || buildDate <= 0L) {
            Log.status(TAG, "started without a target build")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Checked before entering the foreground so a duplicate start does not re-post the
        // progress notification for work it is about to discard. Applying two payloads at once
        // is rejected by update_engine anyway, and a second download would fight the first over
        // the same `.part` file.
        if (job?.isActive == true) {
            Log.status(TAG, "an update is already in progress")
            // stopSelf takes the LATEST startId, so without this the in-flight job's own
            // stopSelf(startId) becomes a no-op and the service outlives its work.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!enterForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        job = scope.launch {
            try {
                run(build, buildDate)
            } finally {
                releaseWakeLock()
                // STOP_FOREGROUND_REMOVE: the progress notification has been replaced by a
                // terminal one, and leaving the ongoing copy behind would show an update
                // permanently stuck at whatever percent it reached.
                ServiceCompat.stopForeground(this@UpdateInstallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15 can time a foreground service out. Stopping cleanly leaves the `.part` file in
     * place, so the next run resumes rather than starting the gigabyte again.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.status(TAG, "foreground service timed out (type $fgsType)")
        job?.cancel()
        stopSelf(startId)
    }

    private suspend fun run(build: String, buildDate: Long) {
        UpdateNotifications.clearResult(this)
        val current = SystemBuild.current()
        val device = SystemBuild.device()
        if (current == null || device.isEmpty()) {
            fail(getString(R.string.error_unknown_build))
            return
        }
        val downloaded = downloadArtifact(build, current.build, device) ?: return
        installArtifact(build, buildDate, device, current.build, downloaded)
    }

    private suspend fun downloadArtifact(
        build: String,
        currentBuild: String,
        device: String,
    ): Boolean? {
        UpdateNotifications.updateProgress(this, getString(R.string.notify_downloading), 0)
        val downloaded = OtaDownloader.download(
            context = this,
            device = device,
            currentBuild = currentBuild,
            targetBuild = build,
        ) { bytes, total ->
            val percent = if (total > 0) ((bytes * 100) / total).toInt() else -1
            UpdateNotifications.updateProgress(this, getString(R.string.notify_downloading), percent)
        }
        return when (downloaded) {
            is OtaDownloader.Result.Downloaded -> downloaded.incremental
            OtaDownloader.Result.NotFound -> {
                fail(getString(R.string.error_no_package))
                null
            }
            is OtaDownloader.Result.Failed -> {
                fail(downloaded.reason)
                null
            }
        }
    }

    private suspend fun installArtifact(
        build: String,
        buildDate: Long,
        device: String,
        currentBuild: String,
        incremental: Boolean,
    ) {
        UpdateNotifications.updateProgress(this, getString(R.string.notify_verifying), 0)
        val result = OtaInstaller.install(
            packageFile = OtaDownloader.updateFile(),
            expected = OtaPackageValidation.Expected(
                buildDateUtcSeconds = buildDate,
                targetBuild = build,
                device = device,
                currentBuild = currentBuild,
                currentFingerprint = SystemBuild.fingerprint(),
            ),
            onVerifyProgress = { percent ->
                UpdateNotifications.updateProgress(this, getString(R.string.notify_verifying), percent)
            },
            onApplyProgress = { percent ->
                UpdateNotifications.updateProgress(this, getString(R.string.notify_installing), percent)
            },
        )

        when (result) {
            OtaInstaller.Result.Applied -> {
                // The payload is on the inactive slot; the running system is untouched until
                // the reboot, which is why waiting for idle costs nothing.
                val store = DataStoreUtils.getInstance(this)
                store.setString(UpdaterPreferences.PENDING_REBOOT_BUILD, build)
                store.setString(UpdaterPreferences.DOWNLOAD_FILE, "")
                UpdateNotifications.rebootPending(this, build, restartIntent(this))
                IdleReboot.schedule(this)
            }

            is OtaInstaller.Result.Failed -> {
                // An incremental update_engine cannot initialise from will fail identically
                // every time. Remember it so the next run goes straight to the full package
                // instead of looping on a download that can never install.
                if (incremental && result.initializationFailure) {
                    val artifacts = OtaDownloadPlan.artifacts(device, currentBuild, build)
                    artifacts.incremental?.let {
                        DataStoreUtils.getInstance(this)
                            .setString(UpdaterPreferences.FAILED_INCREMENTAL, it)
                    }
                }
                OtaDownloader.discard(this)
                fail(result.reason)
            }
        }
    }

    private fun fail(reason: String) {
        Log.error(TAG, "update failed: $reason")
        UpdateNotifications.failed(this, reason)
    }

    // Broad catch is deliberate: startForeground throws undocumented RuntimeExceptions
    // (not just SecurityException) when the platform refuses a background start.
    @Suppress("TooGenericExceptionCaught")
    private fun enterForeground(): Boolean = try {
        acquireWakeLock()
        ServiceCompat.startForeground(
            this,
            UpdateNotifications.ID_PROGRESS,
            UpdateNotifications.progress(this, getString(R.string.notify_preparing), -1),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        true
    } catch (e: Exception) {
        Log.error(TAG, "could not enter the foreground", e)
        releaseWakeLock()
        false
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val EXTRA_BUILD = "build"
        private const val EXTRA_BUILD_DATE = "build_date"

        /** A backstop only. The lock is released in a `finally`; this covers a hard kill. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context, build: String, buildDateUtcSeconds: Long) {
            val intent = Intent(context, UpdateInstallService::class.java)
                .putExtra(EXTRA_BUILD, build)
                .putExtra(EXTRA_BUILD_DATE, buildDateUtcSeconds)
            context.startForegroundService(intent)
        }

        private fun restartIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RebootReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
