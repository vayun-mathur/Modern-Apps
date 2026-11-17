package com.vayunmathur.auto.platform
import androidx.core.content.edit

import android.content.Context

/**
 * Persists the messaging-mirror consent switch.
 *
 * Consent is device-level, not session-level: it survives disconnects and
 * service restarts, and both the notification listener (which decides whether
 * to post at all) and the ch14 owner (which decides whether to send) read it
 * as the source of truth. Defaults to false -- mirroring is fail-closed.
 */
object MessagingPrefs {
    fun isMirroringConsented(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT, false)

    fun setMirroringConsented(context: Context, consented: Boolean) {
        prefs(context).edit { putBoolean(KEY_CONSENT, consented) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "ma_auto_messaging"
    private const val KEY_CONSENT = "mirroring_consented"
}
