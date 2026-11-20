package com.vayunmathur.securemail.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.service.MailSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the relay socket after boot/package-replace when an identity exists. */
class BootReceiver : BroadcastReceiver() {
    // Broad catch is deliberate: onReceive must not throw (calendar/email boot
    // receivers use the same shape), and starting a foreground service from
    // boot throws undocumented RuntimeExceptions on some platform versions.
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(context: Context, intent: Intent) {
        // Only system protected broadcasts restart the socket; any other action is ignored.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        // goAsync keeps the broadcast (and process) alive until the settings
        // read + service start below have actually run; without it the system
        // may kill the process as soon as onReceive returns.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = MailConfig(appContext).settings.first()
                if (settings.address.isNotBlank()) MailSocketService.start(appContext)
            } catch (e: Exception) {
                Log.error(TAG, "could not restart the relay socket after ${intent.action}", e)
            } finally {
                // goAsync() can return null (e.g. when not dispatched by the
                // system, as in host tests); finishing is best-effort.
                pendingResult?.finish()
            }
        }
    }

    /** Logging tag for [BootReceiver]. */
    companion object {
        private const val TAG = "SecureMailBoot"
    }
}
