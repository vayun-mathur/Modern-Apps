package com.vayunmathur.auto.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.network.WifiDirectConnector

/**
 * Shared wireless-setup service, mirroring gearhead's
 * `WirelessSetupSharedService` (+ its `CarService` half): owns the
 * Bluetooth-then-WiFi bring-up the head unit triggers, rather than the
 * pairing screen's manual tap.
 *
 * Minimal: the trigger funnels into `WifiDirectConnector` -- the CDM path
 * (`startFromAssociation`, when the association names a MAC) or the tap
 * stand-in (`start`) otherwise. The service holds no sockets itself; the
 * connector parks the GAL transport in `TransportIntake` for the projection
 * loop, exactly like the manual path.
 */
class WirelessSetupSharedService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The CDM association (see CarCompanionDeviceService) is the trigger
        // when it exists; without one this is the pairing-screen tap in
        // service form -- same connector, same consent semantics.
        val mac = intent?.getStringExtra(EXTRA_BT_ADDRESS)
        if (!mac.isNullOrBlank()) {
            Log.status(TAG, "wireless setup for associated head unit $mac")
            WifiDirectConnector.startFromAssociation(this, mac)
        } else {
            Log.status(TAG, "wireless setup without an association; tap stand-in path")
            WifiDirectConnector.start(this)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Explicit no-op-with-comment: wireless setup is a start-action, not
        // a binder -- a head-unit bind expecting one is safely rejected.
        Log.status(TAG, "wireless-setup bind rejected (start-action service, no binder)")
        return null
    }

    /** Extras and constants for [WirelessSetupSharedService]. */
    companion object {
        /** Optional CDM-associated BT MAC; absent means the tap stand-in path. */
        const val EXTRA_BT_ADDRESS = "com.vayunmathur.auto.EXTRA_BT_ADDRESS"

        private const val TAG = "MaAuto.WirelessSetup"
    }
}
