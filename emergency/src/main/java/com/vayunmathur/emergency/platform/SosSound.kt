package com.vayunmathur.emergency.platform

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService

private const val TAG = "EmergencySosSound"

/**
 * The SOS warning sound.
 *
 * Mirrors GrapheneOS's `EmergencyActionAlarmHelper`: while the countdown runs, the alarm
 * stream is maxed and a warning tone plays; on stop the user's volume is restored. Two
 * deliberate substitutions: the tone comes from `ToneGenerator` (alarm stream,
 * `TONE_CDMA_EMERGENCY_RINGBACK`, looped for the countdown) instead of GrapheneOS's bundled
 * `R.raw.alarm` - a Gradle module cannot ship their audio file and a siren sample would need
 * licensing review - and the sound gate reads our [GestureProvider] static instead of
 * `Settings.Secure` directly (same value, same default).
 *
 * Volume restore is best-effort: if the process dies mid-countdown the stream stays loud
 * until the user touches volume, exactly like the original (whose reset also lives in
 * `stopWarningSound`).
 */
class SosSound(private val context: Context) {

    private var tone: ToneGenerator? = null
    private var savedVolume = UNKNOWN_VOLUME
    private var resetNeeded = false

    /** Starts the warning tone; no-op when the SOS sound is disabled. */
    fun play() {
        if (!GestureProvider.isSoundEnabledStatic(context)) return
        if (tone != null) return
        val audio = context.getSystemService<AudioManager>() ?: return
        runCatching {
            if (savedVolume == UNKNOWN_VOLUME) {
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            resetNeeded = true
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
            ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also {
                tone = it
                // Loop the emergency-ringback tone for the whole countdown; stop() ends it.
                it.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
            }
        }.onFailure {
            Log.status(TAG, "could not start the SOS warning tone", it)
            tone?.release()
            tone = null
        }
    }

    /** Returns the alarm volume the user had before [play] maxed it, or -1 when unknown. */
    fun userAlarmVolume(): Int = savedVolume

    /** Stops the tone and restores the user's alarm volume. */
    fun stop() {
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
        tone = null
        if (resetNeeded && savedVolume != UNKNOWN_VOLUME) {
            val audio = context.getSystemService<AudioManager>()
            runCatching {
                audio?.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            }.onFailure { Log.status(TAG, "could not restore the alarm volume", it) }
            resetNeeded = false
        }
    }

    /** Volume sentinel for [SosSound] when no prior alarm volume was saved. */
    companion object {
        const val UNKNOWN_VOLUME = -1
    }
}
