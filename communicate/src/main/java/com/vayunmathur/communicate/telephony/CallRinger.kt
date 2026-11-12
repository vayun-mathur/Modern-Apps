package com.vayunmathur.communicate.telephony

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.vayunmathur.communicate.domain.ringPlan
import com.vayunmathur.library.log.Log

/** Which call stack a ring belongs to. A line can only stop or silence its own ring. */
enum class RingLine { Sim, InApp, GoogleVoice }

/**
 * One incoming call asking to ring.
 *
 * @property callKey identifies the call, so repeating the same request (every state or details change
 *   re-sends it) is a no-op instead of restarting the ringtone.
 * @property number the caller's dialable number, or null for private callers and peers without one (a
 *   Signal ACI). Drives the Do Not Disturb caller check and the contact's custom ringtone.
 * @property callWaiting whether another call is already live, which turns the ring into the
 *   call-waiting tone.
 */
data class RingRequest(
    val line: RingLine,
    val callKey: Any,
    val number: String?,
    val callWaiting: Boolean,
)

/** The ringer as call-state code sees it; [CallRinger] in production, a recording fake in tests. */
interface IncomingRinger {
    fun ring(request: RingRequest)

    fun stop(line: RingLine)
}

/**
 * Audible + haptic ringing for incoming calls, shared by every line.
 *
 * Nothing else rings for Communicate's calls:
 * - Self-managed Telecom connections ([InAppCallConnectionService], [GoogleVoiceConnectionService])
 *   never get a ringtone from the platform, and no notification (even a `CallStyle` one) loops one.
 * - Carrier/SIM calls do not either: [CommunicateInCallService] declares `IN_CALL_SERVICE_RINGING`,
 *   which tells Telecom's own `Ringer` to skip the ringtone, the vibration and the call-waiting tone
 *   because the dialer plays them. Before #856 nobody did, so SIM calls arrived as a silent heads-up.
 *
 * Every incoming-call notification channel stays silent: the ringer owns audio, otherwise a channel
 * ding would double up with the loop.
 *
 * What plays is decided by [ringPlan] from [RingEnvironmentReader]: the ringer switch (silent, vibrate,
 * normal), the "vibrate for calls" setting, Do Not Disturb (with the platform's starred-contact and
 * repeat-caller exceptions), and whether another call is up (call-waiting tone only). The ringtone is
 * the caller's contact ringtone when set, else the default, played through `USAGE_NOTIFICATION_RINGTONE`.
 *
 * One singleton rather than one per service because only one call rings at a time, and because
 * answer/decline can arrive from the system surface, the notification action, or the in-app screen —
 * whichever path leaves ringing first stops the same ringer.
 */
object CallRinger {
    private const val TAG = "CallRinger"

    /** Relative volume of AOSP Telecom's call-waiting tone (`InCallTonePlayer.RELATIVE_VOLUME_HIPRI`). */
    private const val CALL_WAITING_TONE_VOLUME = 80

    /** The standard ring-vibrate waveform: 1 s on, 1 s off, repeating from index 0. */
    private val RING_VIBRATION_PATTERN = longArrayOf(0, 1000, 1000)

    private val ringtoneAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** The call currently ringing (or silenced while still ringing); null when idle. */
    @Volatile
    private var current: RingRequest? = null

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var vibrator: Vibrator? = null

    @Volatile
    private var toneGenerator: ToneGenerator? = null

    /**
     * Start alerting for [request]. Idempotent per call: the same line, call and call-waiting status is a
     * no-op, so a silenced call stays silent. A different call, or the same call switching between
     * call-waiting and a full ring (the other call hung up), replaces what is playing.
     */
    @Synchronized
    fun start(context: Context, request: RingRequest) {
        if (current?.sameRing(request) == true) return
        releasePlayback()
        current = request
        val app = context.applicationContext
        val environment = RingEnvironmentReader.read(app, request)
        val plan = ringPlan(environment.conditions)
        Log.status(TAG, "incoming ${request.line} call: $plan from ${environment.conditions}")
        if (plan.callWaitingTone) startCallWaitingTone()
        if (plan.vibrate) startVibration(app)
        if (plan.ringtone) startSound(app, environment.customRingtone)
    }

    /** The call on [line] stopped ringing (answered, rejected, ended). Another line's ring is left alone. */
    @Synchronized
    fun stop(line: RingLine) {
        if (current?.line != line) return
        releasePlayback()
        current = null
    }

    /**
     * Mute the ring on [line] while the call keeps ringing — the volume or power key, delivered as
     * `InCallService.onSilenceRinger` / `Connection.onSilence`. The request is kept so the line's
     * next [start] for the same call stays a no-op.
     */
    @Synchronized
    fun silence(line: RingLine) {
        if (current?.line != line) return
        Log.status(TAG, "incoming $line call silenced")
        releasePlayback()
    }

    /** [IncomingRinger] view of this ringer for a call surface holding [context]. */
    fun asIncomingRinger(context: Context): IncomingRinger = object : IncomingRinger {
        override fun ring(request: RingRequest) = start(context, request)

        override fun stop(line: RingLine) = this@CallRinger.stop(line)
    }

    private fun RingRequest.sameRing(other: RingRequest): Boolean =
        line == other.line && callKey == other.callKey && callWaiting == other.callWaiting

    private fun releasePlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        runCatching { toneGenerator?.stopTone() }
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }

    /**
     * AOSP Telecom's call-waiting tone: `TONE_SUP_CALL_WAITING` on the voice-call stream, so it plays
     * in the earpiece of the call already up. The tone's own pattern repeats a short beep about every
     * ten seconds until [releasePlayback] stops it.
     */
    private fun startCallWaitingTone() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, CALL_WAITING_TONE_VOLUME).also {
                it.startTone(ToneGenerator.TONE_SUP_CALL_WAITING)
            }
        }.onSuccess {
            toneGenerator = it
        }.onFailure {
            Log.status(TAG, "could not start the call-waiting tone", it)
        }
    }

    /**
     * Ringtone-usage vibration. On Android 13+ [VibrationAttributes.USAGE_RINGTONE] also lets the system
     * apply the user's ring vibration intensity and silent mode itself, on top of [ringPlan].
     */
    private fun startVibration(app: Context) {
        runCatching {
            val vib = app.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val effect = VibrationEffect.createWaveform(RING_VIBRATION_PATTERN, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect, ringtoneAudioAttributes)
            }
            vibrator = vib
        }.onFailure {
            Log.status(TAG, "could not start call vibration", it)
        }
    }

    /** Plays [customRingtone] when the caller has one, falling back to the default ringtone if it fails. */
    private fun startSound(app: Context, customRingtone: Uri?) {
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val uri = customRingtone ?: default ?: return
        playLooping(app, uri, fallback = default?.takeIf { customRingtone != null && it != customRingtone })
    }

    private fun playLooping(app: Context, uri: Uri, fallback: Uri?) {
        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(app, uri)
            mp.setAudioAttributes(ringtoneAudioAttributes)
            mp.isLooping = true
            mp.setOnPreparedListener { prepared ->
                // stop() may have run while prepare was in flight; only start the current
                // generation, otherwise a stale callback re-rings after hangup.
                synchronized(this) {
                    if (prepared === player) runCatching { prepared.start() } else runCatching { prepared.release() }
                }
            }
            mp.setOnErrorListener { failed, what, extra ->
                Log.status(TAG, "ringtone playback failed what=$what extra=$extra")
                synchronized(this) {
                    if (failed === player && fallback != null) {
                        runCatching { failed.release() }
                        player = null
                        playLooping(app, fallback, fallback = null)
                    }
                }
                true
            }
            // Async: prepare() blocks (content-resolver IO) and this is called from the
            // services' state callbacks.
            mp.prepareAsync()
        }.onSuccess {
            player = mp
        }.onFailure {
            runCatching { mp.release() }
            Log.status(TAG, "could not start the ringtone (custom=${fallback != null})", it)
            // A deleted or unreadable contact ringtone still rings, with the default.
            if (fallback != null) playLooping(app, fallback, fallback = null)
        }
    }
}
