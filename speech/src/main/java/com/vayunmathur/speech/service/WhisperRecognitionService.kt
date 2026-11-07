package com.vayunmathur.speech.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.vayunmathur.library.log.Log
import com.vayunmathur.speech.util.WhisperEngine
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * System speech-recognition service backed by offline **Whisper** (ncnn, ~99 languages,
 * auto-detect). When selected as the device's recognition service, any app using
 * [android.speech.SpeechRecognizer] (Translate, keyboards, …) transcribes fully on-device.
 *
 * Whisper is a whole-utterance model, not a streaming one. To still deliver live results,
 * an **energy VAD** watches for pauses in speech: at each short pause (a natural phrase
 * boundary) the audio-so-far is re-transcribed and delivered via [Callback.partialResults].
 * A longer trailing silence, the client's stopListening, or a 29 s cap ends the utterance
 * and the final pass is delivered via [Callback.results]. Transcription runs on a single
 * background worker so the recording thread never stops draining the mic.
 */
open class WhisperRecognitionService : RecognitionService() {

    private val engine by lazy { createEngine() }

    /** Factory so tests can substitute a fake engine; production uses the real one. */
    protected open fun createEngine(): WhisperEngine = WhisperEngine(applicationContext)
    @Volatile private var session: Session? = null

    override fun onCreate() {
        super.onCreate()
        // Warm up the model (mmapped from APK assets) off the main thread so the first
        // recognition doesn't stall on the initial load.
        Thread { engine.preload() }.start()
    }

    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        if (session != null) {
            safe { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        if (!engine.isModelPresent()) {
            safe { callback.error(SpeechRecognizer.ERROR_SERVER) }
            return
        }
        // BCP-47 (e.g. "en-US") → Whisper ISO-639-1 ("en"); null/blank ⇒ auto-detect.
        val lang = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?.substringBefore('-')?.lowercase()?.takeIf { it.isNotBlank() }
        Session(callback, lang).also { session = it }.start()
    }

    override fun onStopListening(callback: Callback) {
        session?.requestStop()
    }

    override fun onCancel(callback: Callback) {
        session?.cancel()
        session = null
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        engine.close()
        super.onDestroy()
    }

    private fun clearSession(s: Session) {
        if (session === s) session = null
    }

    /** One recognition session: records, VADs for pauses, transcribes. */
    private inner class Session(private val cb: Callback, private val lang: String?) {
        private val chunks = ArrayList<ShortArray>()
        @Volatile private var running = false
        @Volatile private var userStopped = false
        @Volatile private var cancelled = false
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        // All Whisper calls (partials + final) run here, one at a time, so the engine
        // stays single-threaded while the recording thread keeps draining the mic.
        private val transcribeExec = Executors.newSingleThreadExecutor()
        @Volatile private var partialPending = false

        fun start() {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                finishError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return
            }
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuf <= 0) { finishError(SpeechRecognizer.ERROR_AUDIO); return }
            // Broad catch is deliberate: AudioRecord's constructor throws undocumented
            // RuntimeExceptions (not just IllegalArgumentException) on bad state.
            @Suppress("TooGenericExceptionCaught")
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBuf, SAMPLE_RATE * 2),
                )
            } catch (e: Exception) {
                Log.error(TAG, "AudioRecord init failed", e); null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release(); finishError(SpeechRecognizer.ERROR_AUDIO); return
            }
            record = rec
            running = true
            safe { cb.readyForSpeech(Bundle()) }
            try {
                rec.startRecording()
            } catch (e: IllegalStateException) {
                Log.error(TAG, "startRecording failed", e)
                finishError(SpeechRecognizer.ERROR_AUDIO)
                return
            } catch (e: SecurityException) {
                Log.error(TAG, "startRecording failed", e)
                finishError(SpeechRecognizer.ERROR_AUDIO)
                return
            }
            thread = Thread { loop() }.apply { start() }
        }

        fun requestStop() { userStopped = true }

        fun cancel() {
            cancelled = true
            running = false
            releaseRecord()
            transcribeExec.shutdownNow()
        }

        private fun loop() {
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
            val state = VadState()
            while (running && pumpOnce(chunk, state)) {
                // All work happens in pumpOnce; an empty body keeps the loop to one jump.
            }
            if (cancelled || state.noMatchReported) return
            finishAndTranscribe(state.speechStarted)
        }

        /**
         * One loop iteration: drain a chunk, fold it into the VAD and maybe emit a partial.
         * True keeps recording; false exits to the final transcription.
         */
        private fun pumpOnce(chunk: ShortArray, state: VadState): Boolean {
            val n = readChunk(chunk) ?: return false
            if (n <= 0) return true // a failed read that is not a cancel: keep draining
            appendAudio(chunk, n, state)
            updateVad(state)
            maybeEmitPartial(state)
            return !shouldFinish(state)
        }

        /**
         * One 100 ms read. Null ends the session (cancelled); non-positive (without cancel)
         * means keep draining — the caller skips the iteration.
         */
        private fun readChunk(chunk: ShortArray): Int? {
            val n = record?.read(chunk, 0, chunk.size) ?: -1
            if (n > 0) return n
            return if (cancelled) null else n
        }

        private fun appendAudio(chunk: ShortArray, n: Int, state: VadState) {
            chunks.add(chunk.copyOf(n))
            val chunkMs = n * 1000 / SAMPLE_RATE
            state.totalMs += chunkMs
            state.lastChunkMs = chunkMs
            state.lastRms = rms(chunk, n)
            safe { cb.rmsChanged(rmsToDb(state.lastRms)) }
        }

        /** Fold the latest chunk into speech/silence tracking. */
        private fun updateVad(state: VadState) {
            if (state.lastRms > SPEECH_RMS) {
                if (!state.began) {
                    safe { cb.beginningOfSpeech() }
                    state.began = true
                }
                state.speechStarted = true
                state.silenceMs = 0
                // New speech since last partial → allow the next pause to fire.
                state.partialArmed = true
            } else if (state.speechStarted) {
                state.silenceMs += state.lastChunkMs
            }
        }

        /** VAD pause → emit a partial for the audio so far (phrase boundary). */
        private fun maybeEmitPartial(state: VadState) {
            if (!isPartialDue(state)) return
            state.partialArmed = false
            partialPending = true
            val snapshot = flatten()
            transcribeExec.execute {
                val t = engine.transcribe(snapshot, lang)?.trim()
                partialPending = false
                if (!cancelled && !t.isNullOrBlank()) {
                    safe {
                        cb.partialResults(Bundle().apply {
                            putStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(t),
                            )
                        })
                    }
                }
            }
        }

        private fun isPartialDue(state: VadState): Boolean =
            state.speechStarted && state.partialArmed && !partialPending &&
                state.silenceMs in PARTIAL_SILENCE_MS until END_SILENCE_MS

        /**
         * Terminal conditions for the recording loop. True exits the loop (the caller then
         * transcribes): user stop, end-of-utterance silence, or the 29 s cap. A no-speech
         * timeout reports no-match itself and also exits.
         */
        private fun shouldFinish(state: VadState): Boolean {
            if (cancelled) return true
            if (userStopped) return true
            if (state.speechStarted && state.silenceMs >= END_SILENCE_MS) return true
            if (state.totalMs >= MAX_MS) return true
            if (!state.speechStarted && state.totalMs >= NO_SPEECH_MS) {
                finishNoMatch()
                state.noMatchReported = true
                return true
            }
            return false
        }

        /** Mutable VAD/loop progress, threaded through the extracted loop helpers. */
        private inner class VadState {
            var speechStarted = false
            var began = false
            var silenceMs = 0
            var totalMs = 0
            var lastChunkMs = 0
            var lastRms = 0.0
            // Fire at most one partial per pause: armed by any speech, disarmed on fire.
            var partialArmed = false
            /** Set when the no-speech timeout already reported no-match for this session. */
            var noMatchReported = false
        }

        private fun finishAndTranscribe(speechStarted: Boolean) {
            releaseRecord()
            safe { cb.endOfSpeech() }
            if (!speechStarted) { finishNoMatch(); return }
            val audio = flatten()
            // Queue the final pass behind any in-flight partial so the engine is only ever
            // touched by one thread; this is the last task submitted.
            transcribeExec.execute {
                val text = engine.transcribe(audio, lang)?.trim()
                if (cancelled) return@execute
                if (text.isNullOrBlank()) { finishNoMatch(); return@execute }
                val b = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                }
                safe { cb.results(b) }
                clearSession(this)
            }
            transcribeExec.shutdown()
        }

        private fun flatten(): ShortArray {
            val total = chunks.sumOf { it.size }
            val out = ShortArray(total)
            var o = 0
            for (c in chunks) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
            return out
        }

        private fun finishNoMatch() {
            releaseRecord()
            safe { cb.error(SpeechRecognizer.ERROR_NO_MATCH) }
            clearSession(this)
        }

        private fun finishError(code: Int) {
            releaseRecord()
            safe { cb.error(code) }
            clearSession(this)
        }

        private fun releaseRecord() {
            running = false
            try { record?.stop() } catch (_: Throwable) {}
            try { record?.release() } catch (_: Throwable) {}
            record = null
        }
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    private fun rmsToDb(rms: Double): Float =
        (DB_SCALE * log10(rms + RMS_FLOOR)).toFloat().coerceIn(MIN_DB, MAX_DB) / DB_DIVISOR

    companion object {
        private const val TAG = "WhisperRecognition"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val SPEECH_RMS = 500.0      // 16-bit RMS above this counts as speech
        private const val PARTIAL_SILENCE_MS = 350 // a pause this long → emit a partial
        private const val END_SILENCE_MS = 1000    // trailing silence that ends the utterance
        private const val NO_SPEECH_MS = 8000      // give up if nothing is said
        private const val MAX_MS = 29000           // Whisper handles <= 30 s
        /** dB scale factor for the UI meter. */
        private const val DB_SCALE = 10.0
        /** Floor keeping silence at 0 dB rather than -infinity. */
        private const val RMS_FLOOR = 1.0
        /** Meter floor in dB. */
        private const val MIN_DB = 0f
        /** Meter ceiling in dB. */
        private const val MAX_DB = 30f
        /** Maps the 0..30 dB range onto roughly 0..10 for the UI meter. */
        private const val DB_DIVISOR = 3f
    }
}
