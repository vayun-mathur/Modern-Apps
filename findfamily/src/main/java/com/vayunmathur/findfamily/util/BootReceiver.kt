package com.vayunmathur.findfamily.util
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.findfamily.platform.NoShowCheckScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // LOCKED_BOOT_COMPLETED is the only one of these delivered before the user unlocks,
        // and it is the whole reason this receiver is directBootAware. BOOT_COMPLETED does not
        // arrive until after the passcode, so registering for it alone meant nothing ran at all
        // between a reboot and the first unlock.
        val locked = intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        if (!locked &&
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // Only start when fine location is granted; the sharing-enabled check
        // (and the actual start/stop) is handled by syncServiceState.
        if (!LocationServiceController.hasFineLocationPermission(context)) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Both broadcasts fire on a normal boot. The locked pass reads only
                // device-protected state; the unlocked pass reconciles against the real thing.
                if (locked) LocationServiceController.syncServiceStateLocked(appContext)
                else {
                    LocationServiceController.syncServiceState(appContext)
                    // Room is only readable unlocked; re-arm no-show checks here.
                    runCatching { NoShowCheckScheduler.rescheduleAll(appContext) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
