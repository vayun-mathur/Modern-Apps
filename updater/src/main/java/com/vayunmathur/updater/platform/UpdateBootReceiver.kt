package com.vayunmathur.updater.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.updater.notifications.UpdateNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts the periodic check after a boot or an update to this app.
 *
 * `MY_PACKAGE_REPLACED` matters as much as `BOOT_COMPLETED`: replacing the APK puts the app
 * back into the stopped state and cancels its work, so without this the updater would quietly
 * stop checking the first time it updated itself.
 *
 * This deliberately does not start [com.vayunmathur.updater.service.UpdateInstallService]. A
 * boot receiver may not start a `specialUse` foreground service on Android 13 and 14, and there
 * is nothing urgent enough about an OTA to want it competing with everything else that runs at
 * boot. The worker scheduled here runs immediately and decides from there.
 */
class UpdateBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val booted = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!booted && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        UpdateCheckWorker.schedule(appContext)
        if (!booted) return

        // A reboot is the ONLY thing that resolves a pending one, which is why this clear lives
        // here and not in the periodic worker: clearing it on a timer would stop the idle-reboot
        // poll mid-wait and strand a written slot that never gets booted into.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DataStoreUtils.getInstance(appContext)
                    .setString(UpdaterPreferences.PENDING_REBOOT_BUILD, "")
                // Whatever it was asking for, the restart has happened.
                UpdateNotifications.clearResult(appContext)
            } finally {
                // goAsync() can return null (e.g. when not dispatched by the system); finishing
                // is best-effort and must not throw out of the cleanup path.
                pending?.finish()
            }
        }
    }
}
