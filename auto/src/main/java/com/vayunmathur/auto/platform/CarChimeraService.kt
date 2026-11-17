package com.vayunmathur.auto.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.service.ProjectionService

/**
 * Chimera entry point, mirroring gearhead's `CarChimeraService`
 * (`microphone|connectedDevice|location`, `car.service.START`): the GMS-compat
 * action clients bind to start projection.
 *
 * GAL replacement, not GMS: the GAL bring-up lives in [ProjectionService],
 * so this service's whole job is the START action -- it funnels into the
 * projection service and parks. The foreground-service triple matches the
 * Chimera declaration because background mic (ch6) and location (ch7) must
 * survive without a visible activity on API 34+.
 */
class CarChimeraService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            Log.status(TAG, "chimera START; funneling into the projection service")
            ProjectionService.start(this)
        } else {
            Log.debug(TAG, "chimera start without START action; ignoring")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Explicit no-op-with-comment: GMS-compat clients bind for START,
        // not for a GAL interface -- there is no binder to hand out, and a
        // head-unit bind expecting one must be safely rejected, never crash.
        Log.status(TAG, "chimera bind rejected (START-action service, no binder)")
        return null
    }

    /** Start actions and constants for [CarChimeraService]. */
    companion object {
        /** GMS-compat start action, mirroring gearhead's `car.service.START`. */
        const val ACTION_START = "car.service.START"

        private const val TAG = "MaAuto.Chimera"
    }
}
