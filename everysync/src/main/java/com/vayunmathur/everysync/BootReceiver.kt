package com.vayunmathur.everysync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.everysync.sync.SyncScheduler

/**
 * Re-registers the periodic sync after a reboot or app update. WorkManager also
 * restores its own jobs across reboots, but this is a belt-and-suspenders
 * guarantee the schedule survives even if that ever fails.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> SyncScheduler.schedulePeriodic(context.applicationContext)
        }
    }
}
