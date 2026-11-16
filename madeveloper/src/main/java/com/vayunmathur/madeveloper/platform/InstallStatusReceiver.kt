package com.vayunmathur.madeveloper.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.vayunmathur.library.log.Log

/** Receives PackageInstaller session results for pushed installs. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.status(TAG, "Installed $pkg")
            else -> Log.error(
                TAG, "Install failed for $pkg status=$status",
            )
        }
    }

    /** Logging tag and intent action for install results. */
    companion object {
        private const val TAG = "MaDevInstall"
        const val ACTION = "com.vayunmathur.madeveloper.INSTALL_STATUS"
    }
}
