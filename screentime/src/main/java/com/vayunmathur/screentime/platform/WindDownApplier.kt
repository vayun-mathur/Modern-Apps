package com.vayunmathur.screentime.platform

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.vayunmathur.library.log.Log
import androidx.core.content.edit
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
 *
 * Applied-state: [enter] persists exactly what it changed plus the user's
 * prior daltonizer/DND values. [exit] restores that persisted record — never
 * the schedule's *current* flags — so toggling grayscale/DND off mid-window
 * cannot strand them ON, and a user who already had grayscale/DND on before
 * wind-down gets that state back instead of having it clobbered off.
 */
class WindDownApplier(private val context: Context) {

    private val notifications = context.getSystemService<NotificationManager>()
    private val prefs
        get() = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Enter wind-down: grayscale on (if configured), DND on (if configured and allowed). */
    fun enter(grayscale: Boolean, doNotDisturb: Boolean) {
        if (grayscale) applyGrayscale() else if (appliedGrayscale()) restoreGrayscale()
        if (doNotDisturb) applyDnd() else if (appliedDnd()) restoreDnd()
    }

    /** Leave wind-down: restore exactly what [enter] applied, nothing else. */
    fun exit() {
        if (appliedGrayscale()) restoreGrayscale()
        if (appliedDnd()) restoreDnd()
    }

    private fun appliedGrayscale(): Boolean = prefs.getBoolean(KEY_APPLIED_GRAYSCALE, false)
    private fun appliedDnd(): Boolean = prefs.getBoolean(KEY_APPLIED_DND, false)

    private fun applyGrayscale() {
        if (appliedGrayscale()) return // keep the original prior values
        val resolver = context.contentResolver
        val priorEnabled = runCatching {
            Settings.Secure.getInt(resolver, DALTONIZER_ENABLED, 0)
        }.getOrDefault(0)
        val priorMode = runCatching {
            Settings.Secure.getString(resolver, DALTONIZER_MODE)
        }.getOrNull()
        runCatching {
            Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, 1)
            Settings.Secure.putString(resolver, DALTONIZER_MODE, MONOCHROMACY_MODE)
        }.onSuccess {
            prefs.edit {
                putBoolean(KEY_APPLIED_GRAYSCALE, true)
                putInt(KEY_PRIOR_DALTONIZER_ENABLED, priorEnabled)
                if (priorMode != null) putString(KEY_PRIOR_DALTONIZER_MODE, priorMode)
                else remove(KEY_PRIOR_DALTONIZER_MODE)
            }
        }.onFailure { Log.status(TAG, "could not set grayscale=true", it) }
    }

    private fun restoreGrayscale() {
        val resolver = context.contentResolver
        val priorEnabled = prefs.getInt(KEY_PRIOR_DALTONIZER_ENABLED, 0)
        val priorMode = prefs.getString(KEY_PRIOR_DALTONIZER_MODE, null)
        runCatching {
            Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, priorEnabled)
            if (priorMode != null) Settings.Secure.putString(resolver, DALTONIZER_MODE, priorMode)
        }.onSuccess {
            prefs.edit {
                putBoolean(KEY_APPLIED_GRAYSCALE, false)
                remove(KEY_PRIOR_DALTONIZER_ENABLED)
                remove(KEY_PRIOR_DALTONIZER_MODE)
            }
        }.onFailure { Log.status(TAG, "could not restore grayscale", it) }
    }

    private fun applyDnd() {
        if (appliedDnd()) return
        val nm = notifications ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.status(TAG, "no DND policy access; skipping DND change")
            return
        }
        val prior = nm.currentInterruptionFilter
        runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
            .onSuccess {
                prefs.edit {
                    putBoolean(KEY_APPLIED_DND, true)
                    putInt(KEY_PRIOR_FILTER, prior)
                }
            }
            .onFailure { Log.status(TAG, "could not set DND=true", it) }
    }

    private fun restoreDnd() {
        val nm = notifications ?: run {
            Log.status(TAG, "no NotificationManager; cannot restore DND")
            return
        }
        val prior = prefs.getInt(KEY_PRIOR_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
        // Do not gate on isNotificationPolicyAccessGranted: access revoked
        // mid-window must not strand DND on. Attempt the restore anyway;
        // failure is logged and the applied flag is kept so the next
        // reconcile retries.
        runCatching { nm.setInterruptionFilter(prior) }
            .onSuccess {
                prefs.edit {
                    putBoolean(KEY_APPLIED_DND, false)
                    remove(KEY_PRIOR_FILTER)
                }
            }
            .onFailure { Log.status(TAG, "could not restore DND to $prior (policy access=${nm.isNotificationPolicyAccessGranted})", it) }
    }

    private companion object {
        const val PREFS = "screentime_winddown_applied"
        const val KEY_APPLIED_GRAYSCALE = "applied_grayscale"
        const val KEY_APPLIED_DND = "applied_dnd"
        const val KEY_PRIOR_DALTONIZER_ENABLED = "prior_daltonizer_enabled"
        const val KEY_PRIOR_DALTONIZER_MODE = "prior_daltonizer_mode"
        const val KEY_PRIOR_FILTER = "prior_filter"
        const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        const val DALTONIZER_MODE = "accessibility_display_daltonizer_mode"

        /** `AccessibilityManager` monochromacy mode. Documented AOSP value. */
        const val MONOCHROMACY_MODE = "0"
    }
}
