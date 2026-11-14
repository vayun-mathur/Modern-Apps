package com.vayunmathur.emergency.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService
import com.vayunmathur.emergency.platform.EmergencyNumberLookup
import com.vayunmathur.emergency.platform.GestureProvider
import com.vayunmathur.emergency.service.SosCountdownService

private const val TAG = "EmergencySosReceiver"

/**
 * Handles SOS outcomes.
 *
 * Mirrors GrapheneOS's `EmergencyActionBroadcastReceiver`: `MAKE_EMERGENCY_CALL` places the
 * call and then tears the service down (intentional fall-through in the original; an explicit
 * `stopCountdown` call here); `CANCEL_EMERGENCY_COUNTDOWN` cancels the scheduled alarm and
 * stops the service. Both actions are explicit-class intents from our own notification,
 * alarm and screen - the manifest filter is documentation, not an open door.
 *
 * Call placement delegates to Settings' emergency provider
 * (`content://com.android.settings.emergency` / `MAKE_EMERGENCY_CALL`), exactly like
 * `EmergencyActionUtils.placeEmergencyCall`. When Settings has no such provider (a MAOS
 * Settings build that does not implement it), this falls back to an explicit
 * `ACTION_CALL_EMERGENCY` telecom intent carrying the resolved number - so the gesture still
 * dials instead of silently dying.
 */
class SosActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_MAKE_CALL -> {
                Log.status(TAG, "SOS countdown finished; placing the emergency call")
                placeCall(app)
                stopCountdown(app)
            }
            ACTION_CANCEL_COUNTDOWN -> {
                Log.status(TAG, "SOS countdown cancelled; stopping the service")
                stopCountdown(app)
            }
            else -> Log.status(TAG, "unknown SOS action ${intent.action}; ignoring")
        }
    }

    private fun placeCall(app: Context) {
        if (placeViaSettings(app)) return
        placeViaTelecom(app)
    }

    /**
     * Asks Settings to place the call, the GrapheneOS path. Returns false when Settings has
     * no emergency provider so the caller falls back to telecom.
     */
    private fun placeViaSettings(app: Context): Boolean =
        runCatching {
            val result = app.contentResolver.call(
                SETTINGS_EMERGENCY_AUTHORITY,
                SETTINGS_MAKE_CALL,
                null,
                null,
            )
            // A null bundle means "no provider answered"; any bundle means Settings took it.
            result != null
        }.getOrElse {
            Log.status(TAG, "Settings emergency provider did not take the call", it)
            false
        }

    /** Direct emergency dial of the resolved number. Needs CALL_PHONE (runtime grant). */
    private fun placeViaTelecom(app: Context) {
        val number = EmergencyNumberLookup(app)
            .policeNumber(GestureProvider.numberOverrideStatic(app))
        val telecom = app.getSystemService<TelecomManager>()
        if (telecom == null) {
            Log.status(TAG, "no TelecomManager; cannot place the emergency call")
            return
        }
        // Intent.ACTION_CALL_EMERGENCY as a literal: the constant is hidden in this SDK's
        // Intent (same story as the Secure gesture keys), and the action string is stable
        // platform surface ("android.intent.action.CALL_EMERGENCY", routed to the emergency
        // dialer by Telecom).
        val intent = Intent(ACTION_CALL_EMERGENCY).apply {
            data = Uri.fromParts("tel", number, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { app.startActivity(intent) }
            .onFailure { Log.status(TAG, "could not place the emergency call to $number", it) }
    }

    /** Broadcast actions and intent factories for [SosActionReceiver]. */
    companion object {
        const val ACTION_MAKE_CALL = "com.vayunmathur.emergency.broadcast.MAKE_EMERGENCY_CALL"
        const val ACTION_CANCEL_COUNTDOWN =
            "com.vayunmathur.emergency.broadcast.CANCEL_EMERGENCY_COUNTDOWN"

        /** `Intent.ACTION_CALL_EMERGENCY`, hidden in this SDK — see placeViaTelecom. */
        private const val ACTION_CALL_EMERGENCY = "android.intent.action.CALL_EMERGENCY"

        /** Settings' emergency-call provider, the `EmergencyActionUtils` literals. */
        private val SETTINGS_EMERGENCY_AUTHORITY: Uri = Uri.Builder()
            .scheme("content")
            .authority("com.android.settings.emergency")
            .build()
        private const val SETTINGS_MAKE_CALL = "com.android.settings.emergency.MAKE_EMERGENCY_CALL"

        fun makeCallIntent(context: Context): Intent =
            Intent(ACTION_MAKE_CALL).setClass(context, SosActionReceiver::class.java)

        fun makeCallPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0, makeCallIntent(context), PendingIntent.FLAG_IMMUTABLE,
            )

        fun cancelIntent(context: Context): Intent =
            Intent(ACTION_CANCEL_COUNTDOWN).setClass(context, SosActionReceiver::class.java)

        fun cancelPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0, cancelIntent(context), PendingIntent.FLAG_IMMUTABLE,
            )

        /** Cancels the scheduled call alarm and stops the countdown service. */
        fun stopCountdown(context: Context) {
            val app = context.applicationContext
            val alarms = app.getSystemService<AlarmManager>()
            runCatching { alarms?.cancel(makeCallPendingIntent(app)) }
            SosCountdownService.stop(app)
        }
    }
}
