package com.vayunmathur.auto.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.vayunmathur.library.log.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Feeds phone media audio into the car music sink (ch5) via `AudioPlaybackCapture`.
 *
 * This is the only non-root per-app capture path with explicit user consent:
 * the user grants the `MediaProjection` consent dialog once (fail-closed --
 * denial means silence on the head unit, never a crash), plus the in-app
 * switch in [MusicCapturePrefs]. `AudioRecord` loopback needs root,
 * Bluetooth rerouting fights GAL, and ExoPlayer injection only covers our own
 * app -- so capturing the `USAGE_MEDIA` mix is what carries any music app.
 *
 * Data path: an `AudioRecord` built on the capture config reads 16-bit mono
 * PCM on its own thread and hands each chunk to the sink's thread-safe
 * [AudioSinkChannel.feedPcm]; the sink resamples to the negotiated rate and
 * frames at 1024 samples with monotonic timestamps. Capture runs only while
 * the media snapshot says something is playing ([MediaPlaybackMonitor] via
 * [isPlayingNow]) -- an idle capture loop would stream silence and burn CPU.
 *
 * Owns its own thread -- never the pump thread, never the sink's audio
 * thread. The only things shared are the sink's [AudioSinkChannel.feedPcm]
 * (thread-safe) and [MediaProjection] (touched only from the capture thread).
 */
class MusicCapture(
    context: Context,
    private val mediaSink: () -> AudioSinkChannel?,
    /** Current playing flag; the service reads it off the media snapshot. */
    private val isPlayingNow: () -> Boolean = { false },
    private val onEvent: (AudioEvent) -> Unit = {},
) {
    private val appContext = context.applicationContext

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var running = false

    /**
     * Starts capturing into the media sink. Safe from any thread; hops to the
     * capture thread. No-op without the in-app consent, without a projection
     * grant, or when already running. Denial degrades gracefully: the head
     * unit stays silent on ch5 and the session card keeps its zero counters.
     */
    fun start(projection: MediaProjection?) {
        val target = handler ?: run {
            Log.debug(TAG, "dropping music capture start; no capture thread")
            return
        }
        target.post {
            if (running) return@post
            if (!MusicCapturePrefs.isMusicCaptureConsented(appContext)) {
                Log.status(TAG, "music capture not consented; ch5 stays silent")
                return@post
            }
            if (projection == null) {
                Log.status(TAG, "music capture without projection grant; ch5 stays silent")
                return@post
            }
            val record = buildRecord(projection) ?: return@post
            runCatching { record.startRecording() }.onFailure {
                Log.status(TAG, "music capture startRecording failed", it)
                runCatching { record.release() }
                return@post
            }
            this.record = record
            running = true
            Log.status(TAG, "music capture started (${CAPTURE_RATE_HZ}Hz mono)")
            pump()
        }
    }

    /** Stops capturing and releases the record. Safe from any thread. */
    fun stop() {
        val target = handler ?: run {
            running = false
            runCatching { record?.stop() }
            runCatching { record?.release() }
            record = null
            return
        }
        target.post {
            running = false
            runCatching { record?.stop() }
            runCatching { record?.release() }
            record = null
            Log.status(TAG, "music capture stopped")
        }
    }

    /** Starts the capture thread; called with the session, not per channel grant. */
    fun startThread() {
        if (thread != null) return
        thread = HandlerThread("ma-auto-music-capture").also {
            it.start()
            handler = Handler(it.looper)
        }
    }

    /** Stops the thread; the record is released first via [stop]. */
    fun releaseThread() {
        stop()
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    /** Capture-thread loop: read PCM, forward while playing, re-post. */
    private fun pump() {
        val target = handler ?: return
        target.post {
            val active = record
            if (!running || active == null) return@post
            val chunk = ByteArray(CHUNK_BYTES)
            val read = runCatching { active.read(chunk, 0, chunk.size) }.getOrDefault(-1)
            if (read > 0) {
                if (isPlayingNow()) {
                    mediaSink()?.feedPcm(chunk.copyOf(read), CAPTURE_RATE_HZ)
                    onEvent(AudioEvent.MusicCaptured(read.toLong()))
                }
                // Idle snapshots drop the chunk: streaming silence wastes
                // socket bytes and keeps the sink's counters honest (music
                // only, never quiet-room noise).
            } else if (read < 0) {
                Log.status(TAG, "music capture read failed ($read); stopping")
                running = false
                runCatching { active.stop() }
                runCatching { active.release() }
                record = null
                return@post
            }
            if (running) pump()
        }
    }

    /**
     * Builds the capture record: 16-bit mono PCM at [CAPTURE_RATE_HZ] from the
     * `USAGE_MEDIA` mix. Matches the music app's ExoPlayer attributes
     * (`USAGE_MEDIA` / music content), so playback there is capturable; other
     * usages (calls, alarms) are excluded by construction.
     */
    private fun buildRecord(projection: MediaProjection): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CAPTURE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(
            CAPTURE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            Log.status(TAG, "music capture has no viable buffer ($minBytes)")
            return null
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.RECORD_AUDIO,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.status(TAG, "music capture needs the microphone permission")
            return null
        }
        return runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes((minBytes * BUFFER_SIZE_MULTIPLIER).coerceAtLeast(CHUNK_BYTES * 2))
                .build()
        }.getOrElse {
            Log.status(TAG, "music capture AudioRecord build failed", it)
            null
        }
    }

    /**
     * Converts a stereo interleaved 16-bit frame to mono by averaging
     * channels. Unused while the record requests mono directly; kept as the
     * documented fallback if a device refuses the mono format.
     */
    private fun stereoToMono(stereo: ByteArray): ByteArray {
        val shorts = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val mono = ShortArray(shorts.remaining() / 2)
        for (i in mono.indices) {
            val left = shorts.get(i * 2).toInt()
            val right = shorts.get(i * 2 + 1).toInt()
            mono[i] = ((left + right) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return ByteBuffer.allocate(mono.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            asShortBuffer().put(mono)
        }.array()
    }

    private companion object {
        const val TAG = "MaAuto.MusicCapture"

        /** Capture rate; the sink resamples to the head unit's negotiated rate. */
        const val CAPTURE_RATE_HZ = 48_000

        /** ~46ms of 16-bit mono at the capture rate per read. */
        const val CHUNK_BYTES = 4_096

        /** Minimum-buffer multiplier; keeps the record ahead of the read loop. */
        const val BUFFER_SIZE_MULTIPLIER = 4
    }
}
