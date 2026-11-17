package com.vayunmathur.auto.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.service.ProjectionService

/**
 * USB role/port reset receiver, mirroring gearhead's
 * `ConnectionResetReceiver` (`RESET_USB_PORT` / `RESET_GADGET` /
 * `RESET_ROLES` / `RESET_FUNCTION`): after a port, gadget, role or function
 * reset the accessory stack re-enumerates, and the projection listener must
 * be up to catch the re-attach.
 *
 * Minimal: every reset action restarts the listener (idempotent -- the
 * service no-ops when already running) and republishes the projection role
 * (an OTA across the reset could have changed the holder). The reset itself
 * needs MANAGE_USB and stays platform-side; this only re-arms the app side.
 * All four actions are MA-defined equivalents of gearhead's internal chain
 * (a head unit never broadcasts these; gearhead sends them to itself).
 */
class ConnectionResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESET_USB_PORT,
            ACTION_RESET_GADGET,
            ACTION_RESET_ROLES,
            ACTION_RESET_FUNCTION,
            -> {
                Log.status(TAG, "USB reset ${intent.action}; re-arming the projection listener")
                TransportState.publishRole(MaosRoleStatus.isProjectionRoleHeld(context))
                ProjectionService.start(context)
            }
        }
    }

    /** Reset actions handled by [ConnectionResetReceiver]. */
    companion object {
        const val ACTION_RESET_USB_PORT = "com.vayunmathur.auto.RESET_USB_PORT"
        const val ACTION_RESET_GADGET = "com.vayunmathur.auto.RESET_GADGET"
        const val ACTION_RESET_ROLES = "com.vayunmathur.auto.RESET_ROLES"
        const val ACTION_RESET_FUNCTION = "com.vayunmathur.auto.RESET_FUNCTION"

        private const val TAG = "MaAuto.UsbReset"
    }
}
