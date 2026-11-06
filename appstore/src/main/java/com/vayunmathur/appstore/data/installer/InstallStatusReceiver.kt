package com.vayunmathur.appstore.data.installer

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vayunmathur.library.log.Log
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.RestrictedPackages
import com.vayunmathur.appstore.data.TvIncompatiblePackages
import com.vayunmathur.appstore.data.accrescent.DeviceAttributesProvider
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Receives PackageInstaller commit callbacks.
 * Action: com.vayunmathur.appstore.INSTALL_STATUS
 *
 * This is the only place the real outcome of an install arrives. `commit()` returning
 * successfully only means the session was handed over, so a failure here used to reach
 * logcat and nowhere else, leaving the user with the "Installing X" message the ViewModel
 * had already shown and no indication anything had gone wrong.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.vayunmathur.appstore.INSTALL_STATUS"
        private const val TAG = "InstallStatusReceiver"
        private const val UNKNOWN_STATUS = -1
        private const val CHANNEL_ID = "appstore-installs"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, UNKNOWN_STATUS)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> forwardUserAction(context, intent, pkg)
            PackageInstaller.STATUS_SUCCESS -> reportSuccess(context, pkg)
            // The user backing out of the system prompt is not a failure worth reporting back.
            PackageInstaller.STATUS_FAILURE_ABORTED -> reportAborted(pkg)
            else -> reportFailure(context, status, message, pkg)
        }
    }

    /**
     * Surface the system's install-confirmation prompt.
     *
     * With the app in the foreground the prompt is started directly, the same as
     * before. In the background `startActivity` from a manifest receiver is blocked
     * by background-activity-launch restrictions — silently, with no exception — so
     * a background commit's prompt used to vanish without a trace and the update
     * never installed (#898). Now, when no process of this app is in the foreground,
     * a notification carrying the prompt is posted instead so the user can tap it.
     */
    private fun forwardUserAction(context: Context, intent: Intent, packageName: String?) {
        // Forward user action intent
        val userAction = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        } ?: return
        userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isForeground(context)) {
            try {
                context.startActivity(userAction)
            } catch (expected: android.content.ActivityNotFoundException) {
                Log.status(TAG, "Failed to start user action: ${expected.message}")
            } catch (expected: SecurityException) {
                Log.status(TAG, "Failed to start user action: ${expected.message}")
            }
            return
        }
        notifyConfirm(context, userAction, packageName)
    }

    private fun isForeground(context: Context): Boolean {
        val manager =
            context.getSystemService(ActivityManager::class.java) ?: return false
        return manager.runningAppProcesses.orEmpty().any {
            it.uid == Process.myUid() &&
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    /**
     * Post the system prompt behind a tap: the confirmation intent the store cannot
     * launch itself in the background becomes the notification's content intent.
     */
    private fun notifyConfirm(context: Context, userAction: Intent, packageName: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val confirm = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            userAction,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.getString(R.string.install_confirm_title, packageName.orEmpty())
            )
            .setContentText(context.getString(R.string.install_confirm_body))
            .setContentIntent(confirm)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            // Per-package ID: several background prompts can pend at once (an "update
            // all" run), and a single shared ID would let each new one overwrite the last.
            NotificationManagerCompat.from(context)
                .notify(confirmNotificationId(packageName), notification)
        }
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.install_confirm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.install_confirm_channel_description) }
        )
    }

    private fun confirmNotificationId(packageName: String?): Int =
        "install-confirm:${packageName.orEmpty()}".hashCode()

    private fun reportSuccess(context: Context, pkg: String?) {
        Log.status(TAG, "Install success for $pkg")
        pkg?.let { InstallEvents.publish(InstallResult(it, success = true)) }
        // The lesson is stale: a build that installs proves compatibility, so stop
        // suppressing its updates.
        TvIncompatiblePackages.clearOnUpdate(context, pkg)
    }

    private fun reportAborted(pkg: String?) {
        Log.status(TAG, "Install aborted for $pkg")
        pkg?.let { InstallEvents.publish(InstallResult(it, success = false)) }
    }

    private fun reportFailure(context: Context, status: Int, message: String?, pkg: String?) {
        Log.status(TAG, "Install failed for $pkg status=$status message=$message")
        pkg?.let { InstallEvents.publish(InstallResult(it, success = false)) }
        // A source-restricted refusal is the OS's final answer, so stop offering it.
        RestrictedPackages.recordIfRestricted(context, pkg, message)
        // An incompatibility refusal is equally final on this device: without learning
        // it, the update check would re-offer the same update every 12 hours with a
        // terminal toast each time. Recorded like a restriction, consulted the same way.
        // TV-only: a phone ABI-mismatch refusal is not a TV verdict and must not
        // suppress updates, so the receiver passes the device form factor along.
        if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
            TvIncompatiblePackages.recordIfIncompatible(
                context, pkg, DeviceAttributesProvider(context).isTv()
            )
        }
        val reason = failureReason(context, status, message)
        val text = context.getString(R.string.install_failed_reason, pkg.orEmpty(), reason)
        if (!InstallFailureBatch.collect(text)) {
            AppMessages.show(text, duration = AppMessages.Duration.Long)
        }
    }

    private fun failureReason(context: Context, status: Int, message: String?): String {
        return when (status) {
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                context.getString(R.string.install_failure_incompatible)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                context.getString(R.string.install_failure_storage)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                context.getString(R.string.install_failure_conflict)
            else -> message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.install_failure_unknown)
        }
    }
}

/**
 * Holds install failures back while something is updating several apps in a row.
 *
 * [AppMessages] already drops a repeat of an identical message posted within a few
 * seconds (issue #630). That does not cover an "update all": the installs are sequential,
 * so the failures are minutes apart, and each message names its own package, so no two of
 * them are the same string — the user got one snackbar per app, every one saying the same
 * thing. While a batch is running the failures are collected here instead, and whoever
 * started the batch reports them once.
 *
 * A failure that arrives outside a batch is not this object's business and is shown
 * immediately, which is why [collect] reports whether it took ownership.
 */
object InstallFailureBatch {

    private val collected = mutableListOf<String>()
    private var active = false

    /** Start collecting. Any reasons left over from a previous batch are dropped. */
    @Synchronized
    fun begin() {
        active = true
        collected.clear()
    }

    /** Stop collecting and hand back what came in, for a single summary. */
    @Synchronized
    fun end(): List<String> {
        active = false
        val reasons = collected.toList()
        collected.clear()
        return reasons
    }

    /** True when [reason] was collected, false when the caller should show it itself. */
    @Synchronized
    fun collect(reason: String): Boolean {
        if (!active) return false
        collected += reason
        return true
    }
}

/** Terminal outcome of a committed PackageInstaller session, per package. */
data class InstallResult(val packageName: String, val success: Boolean)

/**
 * How the OS's install outcome reaches the rest of the app.
 *
 * [SessionInstaller.commit] only hands the session over; the real install finishes later
 * and its outcome arrives at the manifest-declared [InstallStatusReceiver], which the
 * system instantiates fresh each time and so cannot hold a reference to the ViewModel or
 * the [InstallCoordinator]. This process-wide flow bridges that gap: the receiver publishes
 * each terminal result and whoever cares — the coordinator, to end its "Installing" stage,
 * and the ViewModel, to re-read the device — collects it. Replay is off (a result only
 * matters to collectors already listening) but a small buffer keeps a burst from an
 * "update all" run from being dropped.
 */
object InstallEvents {
    private val _results = MutableSharedFlow<InstallResult>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val results: SharedFlow<InstallResult> = _results.asSharedFlow()

    fun publish(result: InstallResult) {
        _results.tryEmit(result)
    }
}
