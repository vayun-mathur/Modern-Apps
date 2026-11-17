package com.vayunmathur.updater.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.work.startRepeatedTask
import com.vayunmathur.updater.domain.AutoInstallPolicy
import com.vayunmathur.updater.notifications.UpdateNotifications
import com.vayunmathur.updater.service.UpdateInstallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.hours

private const val TAG = "UpdateCheckWorker"

private const val WORK_NAME = "updater_check"

/** Often enough that a release is picked up the same day; rare enough to be free. */
private val CHECK_INTERVAL = 6.hours

/**
 * The periodic check, and the thing that decides whether to act on it.
 *
 * A worker is right here and wrong for the install: this is a single small HTTPS GET, well
 * inside the ten minutes a worker gets, and it should be subject to exactly the batching and
 * network constraints WorkManager applies. When it decides to go ahead it hands off to
 * [UpdateInstallService], which is not.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    // Broad catch is deliberate: a worker must return Result, never throw, and the
    // service start plus checker throw undocumented RuntimeExceptions.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): WorkResult {
        val context = applicationContext
        val store = DataStoreUtils.getInstance(context)

        // An applied slot is waiting for a reboot. Checking again would find the same newer
        // build, re-download it, and be refused by update_engine — only one payload may be
        // applied at a time. The marker is cleared by UpdateBootReceiver, because a reboot is
        // the only thing that resolves it.
        val pendingReboot = store.getStringAwait(UpdaterPreferences.PENDING_REBOOT_BUILD)
        if (!pendingReboot.isNullOrEmpty()) {
            Log.status(TAG, "$pendingReboot is applied and waiting for a reboot; not checking")
            return WorkResult.success()
        }

        // UpdateChecker.check() blocks on a socket, and doWork runs on Dispatchers.Default.
        val result = withContext(Dispatchers.IO) { UpdateChecker.check() }
        store.setLong(UpdaterPreferences.LAST_CHECKED, System.currentTimeMillis())

        val metadata = when (result) {
            is UpdateChecker.Result.Available -> result.metadata
            UpdateChecker.Result.UpToDate -> {
                UpdateNotifications.clearResult(context)
                return WorkResult.success()
            }
            // Retry rather than fail: the realistic cause is a captive portal or a flaky
            // connection, and there is nothing to tell the user about either.
            is UpdateChecker.Result.Failed -> {
                Log.status(TAG, "check failed: ${result.reason}")
                return WorkResult.retry()
            }
        }

        val decision = AutoInstallPolicy.decide(
            AutoInstallPolicy.Conditions(
                autoInstall = store.getBooleanAwait(UpdaterPreferences.AUTO_INSTALL, default = true),
                meteredAllowed = store.getBooleanAwait(
                    UpdaterPreferences.METERED_ALLOWED,
                    default = false,
                ),
                networkMetered = isNetworkMetered(context),
                connected = isConnected(context),
            ),
        )

        when (decision) {
            AutoInstallPolicy.Decision.Proceed -> {
                UpdateNotifications.clearResult(context)
                try {
                    UpdateInstallService.start(context, metadata.build, metadata.buildDateUtcSeconds)
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException: the platform refused a
                    // background start. Fall back to telling the user, who can start it from
                    // the app, rather than failing the worker over something a retry in six
                    // hours will hit again.
                    Log.status(TAG, "could not start the install service", e)
                    UpdateNotifications.updateAvailable(context, metadata.build)
                }
            }

            is AutoInstallPolicy.Decision.Hold -> {
                Log.status(TAG, "not installing ${metadata.build}: ${decision.reason}")
                UpdateNotifications.updateAvailable(context, metadata.build)
            }
        }
        return WorkResult.success()
    }

    /** Periodic-work scheduling and network-state helpers for update checks. */
    companion object {

        fun schedule(context: Context) {
            startRepeatedTask<UpdateCheckWorker>(context, WORK_NAME, CHECK_INTERVAL)
        }

        /**
         * True only when the active network is known to bill by the byte.
         *
         * An unknown network is treated as unmetered because that is what
         * `NET_CAPABILITY_NOT_METERED` reports for wifi and ethernet; the guard exists to catch
         * mobile data, and a capability lookup that fails outright would otherwise block every
         * automatic update forever.
         */
        private fun isNetworkMetered(context: Context): Boolean {
            val manager = context.getSystemService<ConnectivityManager>() ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        private fun isConnected(context: Context): Boolean {
            val manager = context.getSystemService<ConnectivityManager>() ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
