package com.vayunmathur.tuner.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.vayunmathur.library.log.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.concurrent.thread

/** Why capture stopped, so the screen can say something specific. */
enum class CaptureFailure { NO_PERMISSION, UNAVAILABLE }

/**
 * Microphone capture into a lock-free-enough ring buffer, with the settings a tuner needs.
 *
 * The source selection is the part that matters most, and getting it wrong is not recoverable
 * downstream: `MIC` and `VOICE_COMMUNICATION` run AGC, noise suppression and - fatally - a
 * high-pass filter around 80-100 Hz for voice, which removes the entire fundamental of a
 * guitar's low E. `UNPROCESSED` first, then `VOICE_RECOGNITION`, then `CAMCORDER`.
 *
 * 48 kHz because it is the native rate on effectively every modern Android device; asking for
 * 44 100 forces a resampler in the HAL with its own passband ripple and, worse, an unknown rate
 * offset. Float encoding avoids a 16-bit quantisation floor on a note's quiet decay tail.
 */
class AudioCapture(private val context: Context) {
    private val lock = Any()
    private val ring = DoubleArray(CAPACITY)
    private var writeIndex = 0
    private var total = 0L

    /** Samples captured since this instance started. */
    val capturedSamples: Long get() = synchronized(lock) { total }

    /**
     * Copies the most recent `into.size` samples. Returns false while the buffer is still
     * filling, so the caller shows "listening" rather than analysing zeros.
     */
    fun copyLatest(into: DoubleArray): Boolean = synchronized(lock) {
        if (total < into.size || into.size > CAPACITY) return false
        var index = (writeIndex - into.size + CAPACITY) % CAPACITY
        for (i in into.indices) {
            into[i] = ring[index]
            index++
            if (index == CAPACITY) index = 0
        }
        true
    }

    /**
     * Opens the microphone and emits once per [hopSamples] captured. Closes it on cancellation -
     * there is no reason for a foreground service here, and an open microphone is the one part
     * of this app with a real battery cost.
     */
    fun frames(hopSamples: Int): Flow<Result<Unit>> = callbackFlow {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            trySend(Result.failure(CaptureException(CaptureFailure.NO_PERMISSION)))
            close()
            return@callbackFlow
        }
        val record = open()
        if (record == null) {
            trySend(Result.failure(CaptureException(CaptureFailure.UNAVAILABLE)))
            close()
            return@callbackFlow
        }
        synchronized(lock) {
            writeIndex = 0
            total = 0L
            ring.fill(0.0)
        }
        disableVoiceProcessing(record.audioSessionId)

        var running = true
        val reader = thread(name = "tuner-capture") {
            val chunk = FloatArray(hopSamples)
            val shorts = ShortArray(if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) 0 else hopSamples)
            try {
                record.startRecording()
            } catch (t: IllegalStateException) {
                Log.error(TAG, "startRecording failed", t)
                trySend(Result.failure(CaptureException(CaptureFailure.UNAVAILABLE)))
                close()
                return@thread
            }
            while (running) {
                val read = if (shorts.isEmpty()) {
                    record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                } else {
                    val n = record.read(shorts, 0, shorts.size)
                    for (i in 0 until maxOf(n, 0)) chunk[i] = shorts[i] / 32768f
                    n
                }
                if (read <= 0) break
                append(chunk, read)
                trySend(Result.success(Unit))
            }
        }

        awaitClose {
            running = false
            reader.join(JOIN_TIMEOUT_MS)
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun append(chunk: FloatArray, count: Int) = synchronized(lock) {
        for (i in 0 until count) {
            ring[writeIndex] = chunk[i].toDouble()
            writeIndex++
            if (writeIndex == CAPACITY) writeIndex = 0
        }
        total += count
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun open(): AudioRecord? {
        val encodings = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
        for (encoding in encodings) {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, encoding)
            if (minimum <= 0) continue
            val record = try {
                AudioRecord(source(), SAMPLE_RATE, CHANNEL, encoding, minimum * BUFFER_MULTIPLIER)
            } catch (t: IllegalArgumentException) {
                Log.error(TAG, "AudioRecord init failed for encoding $encoding", t)
                null
            }
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                // Read the source back off the stream instead of trusting what we asked for, so a
                // platform substituting a processed source at least shows up in the log.
                if (record.audioSource != MediaRecorder.AudioSource.UNPROCESSED) {
                    Log.status(
                        TAG,
                        "UNPROCESSED unavailable, capturing with source ${record.audioSource} - " +
                            "a voice high-pass may attenuate everything below ~100 Hz",
                    )
                }
                return record
            }
            record?.release()
        }
        return null
    }

    private fun source(): Int {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessed =
            manager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return when {
            unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    /** Some platforms attach these to the session regardless of the source; turn them off. */
    private fun disableVoiceProcessing(sessionId: Int) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = false }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.apply { enabled = false }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.apply { enabled = false }
            }
        }.onFailure { Log.status(TAG, "could not detach voice processing", it) }
    }

    companion object {
        /** Native rate on effectively every modern device. Never resample. */
        const val SAMPLE_RATE = 48_000

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO

        /** ~1.4 s, comfortably more than the 786 ms bottom-octave CQT kernel. */
        private const val CAPACITY = 1 shl 16

        private const val BUFFER_MULTIPLIER = 4
        private const val JOIN_TIMEOUT_MS = 500L
        private const val TAG = "AudioCapture"
    }
}

/** Carries a [CaptureFailure] out of the capture flow. */
class CaptureException(val failure: CaptureFailure) : Exception(failure.name)
