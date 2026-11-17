package com.vayunmathur.auto.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.service.ProjectionService

/**
 * Wireless-startup broadcast receiver, mirroring gearhead's
 * `WirelessStartupReceiver`: the platform announcing a wireless head unit
 * starts the setup chain without the app being open.
 *
 * Funnels into the `BT_START` chain (`CarStartupService`), which owns the
 * trigger decision (CDM association vs tap stand-in). Never starts the
 * connector directly: the chain is the single entry point, so boot, HU
 * startup and wireless announcements cannot race three different bring-ups.
 */
class WirelessStartupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIRELESS_STARTUP) return
        Log.status(TAG, "wireless startup announced; entering the BT_START chain")
        context.startForegroundService(
            Intent(context, CarStartupService::class.java)
                .setAction(CarStartupService.ACTION_BT_START),
        )
        ProjectionService.start(context)
    }

    /** Wireless startup actions handled by [WirelessStartupReceiver]. */
    companion object {
        /** Platform announcement of a wireless head unit (MA-defined). */
        const val ACTION_WIRELESS_STARTUP = "com.vayunmathur.auto.WIRELESS_STARTUP"

        private const val TAG = "MaAuto.WirelessStartup"
    }
}
