package com.vayunmathur.auto.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.network.UsbConnector

/**
 * TPlus USB receiver, mirroring gearhead's `CarUsbReceiverTPlus`: a TPlus
 * head unit announces on its own action (not the stock ATTACHED), and needs
 * the same permission round-trip plus the TPlus framing the GAL bytes ride.
 *
 * Minimal: the TPlus announce funnels into [UsbConnector]'s attach path
 * (same filter semantics, same fd-as-stream open -- see the TPlus note in
 * `UsbConnector.onAttached`), so bring-up stays one path. The framing
 * difference is HU-side; the GAL bytes ride unchanged.
 */
class UsbTPlusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_TPLUS_ATTACHED &&
            intent.action != ACTION_USB_TPLUS_DETACHED
        ) {
            return
        }
        Log.status(TAG, "TPlus accessory event ${intent.action}; entering the USB attach path")
        if (!UsbConnector.onAccessoryIntent(context, intent)) {
            // Not a stock accessory extra: re-drive as a force-start so the
            // last accessory still brings up without a replug.
            UsbConnector.forceStart(context)
        }
    }

    /** USB attach actions handled by [UsbTPlusReceiver]. */
    companion object {
        /** TPlus head-unit attach announcement (MA-defined, mirrors TPlus). */
        const val ACTION_USB_TPLUS_ATTACHED = "com.vayunmathur.auto.USB_TPLUS_ATTACHED"

        /** TPlus head-unit detach announcement (MA-defined, mirrors TPlus). */
        const val ACTION_USB_TPLUS_DETACHED = "com.vayunmathur.auto.USB_TPLUS_DETACHED"

        private const val TAG = "MaAuto.UsbTPlus"
    }
}
