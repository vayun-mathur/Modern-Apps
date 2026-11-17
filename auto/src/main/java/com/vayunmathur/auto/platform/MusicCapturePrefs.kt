package com.vayunmathur.auto.platform
import androidx.core.content.edit

import android.content.Context

/**
 * Persists the music-capture consent switch.
 *
 * Consent is device-level, not session-level: it survives disconnects and
 * service restarts, and the ch5 music feed reads it as the source of truth
 * alongside the runtime MediaProjection grant. Defaults to false -- music
 * never leaves the phone until the user opts in (fail-closed).
 */
object MusicCapturePrefs {
    fun isMusicCaptureConsented(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT, false)

    fun setMusicCaptureConsented(context: Context, consented: Boolean) {
        prefs(context).edit { putBoolean(KEY_CONSENT, consented) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "ma_auto_music_capture"
    private const val KEY_CONSENT = "music_capture_consented"
}
