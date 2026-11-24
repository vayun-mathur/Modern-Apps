package com.vayunmathur.appstore.wear.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.google.android.gms.wearable.Wearable
import com.vayunmathur.appstore.wear.data.WearStore
import com.vayunmathur.appstore.wear.domain.InstallResult
import com.vayunmathur.appstore.wear.domain.WatchAppEvent
import com.vayunmathur.appstore.wear.domain.WearSyncProtocol
import com.vayunmathur.library.log.Log
import kotlinx.serialization.encodeToString

/**
 * Receives the PackageInstaller verdict for watch installs.
 *
 * STATUS_PENDING_USER_ACTION is the honest centre of this feature: the OS is
 * asking the wearer, and this receiver's job is only to put that prompt in
 * front of them (and, later, to report what they and the OS decided).
 */
class InstallResultReceiver : BroadcastReceiver() {

    /** Holds broadcast constants for install-result callbacks. */
    companion object {
        const val ACTION_INSTALL_RESULT = "com.vayunmathur.appstore.wear.INSTALL_RESULT"
        const val EXTRA_PACKAGE = "package"
        private const val TAG = "InstallResultReceiver"
        private const val UNKNOWN_STATUS = -1
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, UNKNOWN_STATUS)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: return
        val store = WearStore.get(context)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val userAction = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(userAction) }
                    .onFailure { Log.status(TAG, "show install prompt failed: ${it.message}") }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                store.applyEvent(packageName, WatchAppEvent.InstallFinished(installed = true))
                store.publishWatchState()
                report(context, InstallResult(packageName, store.installedVersion(packageName), true))
            }

            else -> {
                // Includes STATUS_FAILURE_ABORTED (wearer backed out): a real
                // outcome the phone should show, not a silent disappearance.
                store.applyEvent(packageName, WatchAppEvent.InstallFinished(installed = false))
                store.publishWatchState()
                report(
                    context,
                    InstallResult(
                        packageName,
                        0L,
                        installed = false,
                        message.ifBlank { "install failed (status $status)" },
                    ),
                )
            }
        }
    }

    private fun report(context: Context, result: InstallResult) {
        runCatching {
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(
                        node.id,
                        WearSyncProtocol.INSTALL_RESULT_PATH,
                        WearSyncProtocol.json.encodeToString(result).encodeToByteArray(),
                    )
                }
            }
        }.onFailure { Log.status(TAG, "report result failed: ${it.message}") }
    }
}
