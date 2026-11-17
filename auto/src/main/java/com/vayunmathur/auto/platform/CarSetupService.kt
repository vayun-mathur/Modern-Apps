package com.vayunmathur.auto.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.vayunmathur.library.log.Log

/**
 * Setup handshake service, mirroring gearhead's `CarSetupServiceImpl`
 * (`CAR_SETUP_SERVICE`): the head unit binds here during first-time setup
 * before projection starts.
 *
 * Minimal safe handlers: the setup handshake has no MA counterpart (setup
 * is the pairing screen plus the USB permission round-trip), so the bind is
 * explicitly rejected with a comment -- never crash, never claim a setup
 * the phone cannot complete.
 */
class CarSetupService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        // Explicit no-op-with-comment: the HU setup-handshake bind is safely
        // rejected (null) -- MA setup is the pairing screen, not a bound
        // handshake, and claiming one would stall first-time setup.
        Log.status(TAG, "car-setup bind rejected (pairing-screen model)")
        return null
    }

    /** Setup actions and constants for [CarSetupService]. */
    companion object {
        /** Setup handshake action, mirroring gearhead's `CAR_SETUP_SERVICE`. */
        const val ACTION_CAR_SETUP_SERVICE = "com.vayunmathur.auto.CAR_SETUP_SERVICE"
        private const val TAG = "MaAuto.CarSetup"
    }
}
