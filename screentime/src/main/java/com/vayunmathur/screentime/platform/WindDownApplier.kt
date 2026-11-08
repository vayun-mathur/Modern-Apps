package com.vayunmathur.screentime.platform

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService

private const val TAG = "ScreenTimeWindDown"

/**
 * Applies and clears the wind-down state: grayscale plus Do Not Disturb.
 *
 * Grayscale path (see Phase 0b): the `CONTROL_DISPLAY_*` permissions are
 * `signature|privileged` - not role-granted - so this writes the accessibility daltonizer
 * keys via `Settings.Secure` instead. `WRITE_SECURE_SETTINGS` is
 * `signature|privileged|development|role|installer`, granted by `SYSTEM_WELLBEING`, which
 * makes this a role-privileged write rather than a hack: the same keys back the system's
 * own grayscale toggle. Monochromacy mode desaturates fully; the saturation level is left
 * at the user's own setting.
 *
 * DND path (see Phase 0c): `ACCESS_NOTIFICATION_POLICY` is a normal permission; the user
 * grants the policy access once, after which schedules toggle it directly through
 * `NotificationManager`. If access was never granted, wind-down still grayscales - a missing
 * quiet mode must never block the visible half.
 */
class WindDownApplier(private val context: Context) {

    private val notifications = context.getSystemService<NotificationManager>()

    /** Enter wind-down: grayscale on (if configured), DND on (if configured and allowed). */
    fun enter(grayscale: Boolean, doNotDisturb: Boolean) {
        if (grayscale) setGrayscale(true)
        if (doNotDisturb) setDnd(true)
    }

    /** Leave wind-down: restore exactly what [enter] changed, nothing else. */
    fun exit(grayscale: Boolean, doNotDisturb: Boolean) {
        if (grayscale) setGrayscale(false)
        if (doNotDisturb) setDnd(false)
    }

    private fun setGrayscale(on: Boolean) {
        val resolver = context.contentResolver
        runCatching {
            // String keys, not Settings.Secure constants: the daltonizer constants are absent
            // from this SDK's Settings.Secure, and the keys are stable AOSP surface.
            Settings.Secure.putInt(
                resolver,
                "accessibility_display_daltonizer_enabled",
                if (on) 1 else 0,
            )
            if (on) {
                Settings.Secure.putString(
                    resolver,
                    "accessibility_display_daltonizer_mode",
                    daltonizerMode(),
                )
            }
        }.onFailure { Log.status(TAG, "could not set grayscale=$on", it) }
    }

    /**
     * The daltonizer mode value for full monochromacy.
     *
     * This is always the documented monochromacy constant: wind-down grayscale must
     * desaturate fully, not reapply whatever transform the key currently holds.
     */
    private fun daltonizerMode(): String = MONOCHROMACY_MODE

    private fun setDnd(on: Boolean) {
        val nm = notifications ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.status(TAG, "no DND policy access; skipping DND change")
            return
        }
        runCatching {
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL,
            )
        }.onFailure { Log.status(TAG, "could not set DND=$on", it) }
    }

    private companion object {
        /** `AccessibilityManager` monochromacy mode. Documented AOSP value. */
        const val MONOCHROMACY_MODE = "0"
    }
}
