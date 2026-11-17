package com.vayunmathur.parentalcontrols.platform

import android.content.Context
import androidx.core.content.edit

/**
 * The device-wide "Controls for this phone" master switch.
 *
 * Kept in SharedPreferences rather than Room: like [LimitState] it must be readable from a
 * broadcast receiver on the main thread before any coroutine exists, and it gates enforcement
 * at the top of [Enforcer.reconcile]. When off, nothing is blocked and no observers are armed.
 * Defaults to on so a freshly provisioned device enforces the parent's rules immediately.
 */
object SupervisionMaster {

    private const val PREFS = "parentalcontrols_master"
    private const val KEY_ENABLED = "controls_enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
