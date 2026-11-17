@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
)

package com.vayunmathur.maps.platform
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.domain.NavigationProgress

import kotlin.uuid.Uuid
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.vayunmathur.library.log.Log
import com.vayunmathur.maps.R
import com.vayunmathur.maps.domain.RouteService.Step
import java.util.Locale

/**
 * Voice-guidance scheduler for navigation. Wraps Android's [TextToSpeech].
 *
 * Plays each step's instruction at three distance thresholds before the
 * maneuver (1 km, 300 m, 100 m), plus an immediate cue when the user
 * transitions onto a new step. Each (stepIndex, threshold) is announced at
 * most once until [reset] is called (e.g. on route recalculation).
 *
 * Lives as a top-level singleton so the foreground service and any UI can
 * share a single TTS engine without re-initializing it on every navigation
 * start.
 */
object NavigationTts {

    private const val TAG = "NavTts"

    private val initialized = AtomicBoolean(false)
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Stepwise record of which distance thresholds we've already announced. */
    private val announced: MutableMap<Int, MutableSet<Int>> = mutableMapOf()

    /** Track the last announced step so we know to fire a step-transition cue. */
    @Volatile private var lastAnnouncedStepIndex: Int = -1

    /**
     * Outstanding TTS utterances currently being played or queued. Used to
     * keep AudioFocus held across rapidly-fired back-to-back cues — we only
     * abandon focus when the queue actually drains, not after the FIRST
     * utterance's onDone fires.
     */
    private val outstandingUtterances = AtomicInteger(0)

    /** Thresholds (m) — must be in descending order. */
    private val thresholdsMeters = intArrayOf(1000, 300, 100)

    /** Metres per kilometre, for the spoken distance. */
    private const val METERS_PER_KILOMETER = 1000
    /** Short-range cue threshold (m): below this the cue is "now"-style. */
    private const val SHORT_CUE_THRESHOLD_M = 100

    /** Application context for resolving localized spoken phrases. */
    private var appContext: Context? = null

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { onUtteranceFinished() }
                    @Deprecated("Old API")
                    override fun onError(utteranceId: String?) { onUtteranceFinished() }
                    override fun onError(utteranceId: String?, errorCode: Int) { onUtteranceFinished() }
                })
                Log.status(TAG, "TTS initialized")
            } else {
                Log.status(TAG, "TTS init failed: status=$status")
            }
        }
    }

    /** Called from the utterance listener: only abandon focus when no more are pending. */
    private fun onUtteranceFinished() {
        if (outstandingUtterances.decrementAndGet() <= 0) {
            // Reset to 0 in case of decrement-below-zero from spurious callbacks.
            outstandingUtterances.set(0)
            abandonFocus()
        }
    }

    fun shutdown() {
        if (!initialized.compareAndSet(true, false)) return
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        outstandingUtterances.set(0)
        abandonFocus()
        audioManager = null
        focusRequest = null
        announced.clear()
        lastAnnouncedStepIndex = -1
    }

    /** Drop all announcement bookkeeping (call on recalc or stop). */
    fun reset() {
        announced.clear()
        lastAnnouncedStepIndex = -1
        runCatching { tts?.stop() }
        // tts.stop() doesn't necessarily fire onDone for queued utterances,
        // so manually clear the in-flight counter and drop focus.
        outstandingUtterances.set(0)
        abandonFocus()
    }

    /**
     * Called from the navigation service every time progress updates. Decides
     * whether to fire a new announcement.
     */
    fun onProgressUpdate(progress: NavigationProgress, steps: List<Step>) {
        val stepIdx = progress.currentStepIndex
        if (stepIdx !in steps.indices) return

        // Step transition: speak the new step's instruction immediately.
        if (stepIdx != lastAnnouncedStepIndex && lastAnnouncedStepIndex >= 0) {
            val instr = steps[stepIdx].navInstruction.instructions
            if (instr.isNotBlank()) speak(instr)
            // Mark all thresholds for the new step as already-considered so
            // we don't immediately re-fire the 1000m cue while the user is
            // still close to the maneuver.
            announced.getOrPut(stepIdx) { mutableSetOf() }
        }
        lastAnnouncedStepIndex = stepIdx

        // Threshold-based cues for the NEXT step's maneuver (not the current
        // step's start). distanceToNextManeuver counts down to step (idx+1).
        val nextIdx = stepIdx + 1
        if (nextIdx !in steps.indices) return

        val nextInstr = steps[nextIdx].navInstruction.instructions
        if (nextInstr.isBlank()) return

        val alreadyAnnounced = announced.getOrPut(nextIdx) { mutableSetOf() }
        for (threshold in thresholdsMeters) {
            if (progress.distanceToNextManeuver <= threshold && threshold !in alreadyAnnounced) {
                val phrase = phraseFor(threshold, nextInstr)
                speak(phrase)
                alreadyAnnounced.add(threshold)
                // Only fire one threshold per update — if multiple cross at
                // once (e.g. very short step), the next update picks them up.
                break
            }
        }
    }

    private fun phraseFor(thresholdMeters: Int, instruction: String): String {
        val ctx = appContext ?: return instruction
        return when {
            thresholdMeters >= METERS_PER_KILOMETER -> {
                val km = thresholdMeters / METERS_PER_KILOMETER
                val distance = ctx.resources.getQuantityString(R.plurals.tts_kilometers, km, km)
                ctx.getString(R.string.tts_in_distance, distance, instruction)
            }
            thresholdMeters >= SHORT_CUE_THRESHOLD_M ->
                ctx.resources.getQuantityString(R.plurals.tts_in_meters, thresholdMeters, thresholdMeters, instruction)
            else -> ctx.getString(R.string.tts_now, instruction)
        }
    }

    private fun speak(text: String) {
        val engine = tts ?: return
        if (!requestFocus()) return
        outstandingUtterances.incrementAndGet()
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, Uuid.random().toString())
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return false
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build().also { focusRequest = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
    }
}
