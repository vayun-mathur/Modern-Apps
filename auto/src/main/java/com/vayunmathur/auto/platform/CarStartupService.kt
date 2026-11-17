package com.vayunmathur.auto.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.network.UsbConnector
import com.vayunmathur.auto.service.ProjectionService

/**
 * Head-unit startup chain, mirroring gearhead's `CarStartupServiceImpl`
 * (`BT_START` + `START_USB_PROJECTION`) plus `GearheadCarStartupService`
 * (`CAR_STARTUP_NOTIFICATION`): the head unit announcing itself starts the
 * phone side without the app being open.
 *
 * Chain: `BT_START` (wireless head unit over Bluetooth) drives the wireless
 * trigger path through the CDM service when associated, and
 * `START_USB_PROJECTION` force-starts USB bring-up for the last accessory
 * through [UsbConnector]. `CAR_STARTUP_NOTIFICATION` (HU startup announced)
 * starts the projection listener like boot does. Manual/boot start stays
 * the fallback when no chain action arrives.
 */
class CarStartupService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BT_START -> {
                // Wireless start: the CDM service owns the association (see
                // CarCompanionDeviceService); without one there is no MAC to
                // trigger on, so fall back to listening and let the pairing
                // screen stand in.
                Log.status(TAG, "BT_START; ensuring the projection listener is up")
                ProjectionService.start(this)
            }
            ACTION_START_USB_PROJECTION -> {
                Log.status(TAG, "START_USB_PROJECTION; force-starting USB bring-up")
                UsbConnector.forceStart(this)
                ProjectionService.start(this)
            }
            ACTION_CAR_STARTUP_NOTIFICATION -> {
                Log.status(TAG, "CAR_STARTUP_NOTIFICATION; starting the projection listener")
                ProjectionService.start(this)
            }
            else -> Log.debug(TAG, "startup service without a chain action; ignoring")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Explicit no-op-with-comment: the startup chain is start-actions,
        // not a binder -- a head-unit bind expecting one is safely rejected.
        Log.status(TAG, "startup bind rejected (start-action service, no binder)")
        return null
    }

    /** Startup actions and constants for [CarStartupService]. */
    companion object {
        /** Wireless head unit announcing over Bluetooth. */
        const val ACTION_BT_START = "com.vayunmathur.auto.BT_START"

        /** Wired head unit asking for projection without a fresh attach. */
        const val ACTION_START_USB_PROJECTION = "com.vayunmathur.auto.START_USB_PROJECTION"

        /** Head-unit startup notification (unconsumed until now: manual/boot start only). */
        const val ACTION_CAR_STARTUP_NOTIFICATION = "com.vayunmathur.auto.CAR_STARTUP_NOTIFICATION"

        private const val TAG = "MaAuto.Startup"
    }
}
