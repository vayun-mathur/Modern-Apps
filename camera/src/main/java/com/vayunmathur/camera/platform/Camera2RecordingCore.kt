package com.vayunmathur.camera.platform

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Surface
import com.vayunmathur.camera.platform.camera2.EncoderSurfaceHandoff
import com.vayunmathur.camera.platform.camera2.MuxerStartPolicy
import com.vayunmathur.camera.platform.camera2.RecordingSessionOwnership
import com.vayunmathur.camera.platform.camera2.SharedRecordingCoreLifecycle
import com.vayunmathur.library.log.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Shared codec/muxer/drain core (SloMoEnginePolicy piece 2), extracted from
 * [Camera2VideoRecordingEngine] so the same encoder, muxer and drain can
 * run under an externally-owned session — the constrained high-speed
 * session — instead of only under the engine's own regular session.
 *
 * This core owns no camera device and no capture session. Its caller owns
 * the session, lists [prepareEncoderSurface]'s surface as a configure-time
 * target, and drives the stop boundary (stop repeating first, then
 * [signalVideoEos]). The engine keeps that exact flow for its own regular
 * session; the slo-mo glue drives it from `Camera2HighSpeedSession`.
 *
 * Ordering is enforced twice: [EncoderSurfaceHandoff] for the codec-first
 * surface handoff and [SharedRecordingCoreLifecycle] for drain/EOS/finalize.
 */
class Camera2RecordingCore(
    private val config: CustomRecordingConfig,
    private val initialBitrateBps: () -> Int,
    ownership: RecordingSessionOwnership = RecordingSessionOwnership.ENGINE_OWNED,
) {
    val lifecycle = SharedRecordingCoreLifecycle(ownership)
    private val encoderHandoff = EncoderSurfaceHandoff()

    private var preparedEncoderSurface: Surface? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null

    @Volatile private var muxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    @Volatile private var draining = false
    private var drainThread: Thread? = null
    private var audioThread: Thread? = null

    val isMuxerStarted: Boolean get() = muxerStarted

    /**
     * Configures the video codec and exposes its input surface before any
     * camera session is opened. Idempotent; returns null on failure.
     */
    fun prepareEncoderSurface(): Surface? {
        preparedEncoderSurface?.let { return it }
        return try {
            if (videoCodec == null) configureVideoCodec()
            val surface = videoCodec!!.createInputSurface()
            encoderHandoff.markSurfaceExposed()
            lifecycle.markSurfacePrepared()
            preparedEncoderSurface = surface
            surface
        } catch (e: IllegalStateException) {
            Log.error("Camera2Recorder", "prepareEncoderSurface failed", e)
            null
        }
    }

    fun preparedEncoderSurface(): Surface? = preparedEncoderSurface

    private fun configureVideoCodec() {
        val videoFormat = MediaFormat.createVideoFormat(config.videoMime, config.width, config.height)
            .apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, initialBitrateBps())
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                if (config.captureFps != config.fps) {
                    setInteger(MediaFormat.KEY_CAPTURE_RATE, config.captureFps)
                }
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec)
            }
        videoCodec = MediaCodec.createEncoderByType(config.videoMime).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        encoderHandoff.markCodecConfigured()
    }

    /** Creates the muxer and (when enabled) the audio codec. Video codec must already be configured. */
    fun setupMuxerAndAudio(outputFile: File) {
        if (videoCodec == null) configureVideoCodec()
        if (config.audioEnabled) {
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, config.audioSampleRate, 1,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrateBps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_BYTES)
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun startVideoCodec() {
        videoCodec?.start()
    }

    fun markSessionStreaming() {
        encoderHandoff.markSessionStreaming()
    }

    fun startDrain() {
        draining = true
        lifecycle.markDraining()
        drainThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            while (draining || muxerStarted) {
                drainCodec(videoCodec, isVideo = true, bufferInfo)
                drainCodec(audioCodec, isVideo = false, bufferInfo)
                if (!draining) break
                Thread.sleep(DRAIN_POLL_MS)
            }
        }, "Camera2RecorderDrain").apply { start() }
    }

    private fun drainCodec(codec: MediaCodec?, isVideo: Boolean, info: MediaCodec.BufferInfo) {
        codec ?: return
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (_: IllegalStateException) {
                return
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val m = muxer ?: return
                    if (isVideo) videoTrackIndex = m.addTrack(format) else audioTrackIndex = m.addTrack(format)
                    if (
                        !muxerStarted && MuxerStartPolicy.shouldStart(
                            hasVideoTrack = videoTrackIndex >= 0,
                            hasAudioTrack = audioTrackIndex >= 0,
                            audioEnabled = config.audioEnabled,
                        )
                    ) {
                        m.start()
                        muxerStarted = true
                    }
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index >= 0 -> {
                    if (muxerStarted && info.size > 0) {
                        val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: continue
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer?.writeSampleData(if (isVideo) videoTrackIndex else audioTrackIndex, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /** AudioRecord (mono) -> audio encoder input buffers, with codec timestamps. No-op without audio. */
    fun startAudio() {
        if (!config.audioEnabled) return
        val minBuffer = AudioRecord.getMinBufferSize(
            config.audioSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.error("Camera2Recorder", "AudioRecord min buffer unavailable: $minBuffer")
            return
        }
        @SuppressLint("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            config.audioSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, AUDIO_MAX_INPUT_BYTES),
        )
        audioRecord = record
        record.startRecording()
        audioThread = Thread({
            val chunk = ByteArray(AUDIO_CHUNK_BYTES)
            var presentationUs = 0L
            val codec = audioCodec ?: return@Thread
            while (draining) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                val inputIndex = try {
                    codec.dequeueInputBuffer(AUDIO_DEQUEUE_TIMEOUT_US)
                } catch (_: IllegalStateException) {
                    break
                }
                if (inputIndex < 0) continue
                val input = codec.getInputBuffer(inputIndex) ?: continue
                input.clear()
                input.put(chunk, 0, minOf(read, input.remaining()))
                val flags = if (!draining) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                codec.queueInputBuffer(inputIndex, 0, minOf(read, AUDIO_CHUNK_BYTES), presentationUs, flags)
                presentationUs += read.toLong() * 1_000_000L / (config.audioSampleRate * AUDIO_BYTES_PER_SAMPLE)
            }
        }, "Camera2RecorderAudio").apply { start() }
    }

    fun signalVideoEos() {
        try {
            videoCodec?.signalEndOfInputStream()
            encoderHandoff.markEosSignalled()
            lifecycle.markEosSignalled()
        } catch (_: IllegalStateException) {
        }
        draining = false
    }

    fun stopAudio() {
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
    }

    fun joinDrainAndAudio(timeoutMs: Long = DRAIN_JOIN_TIMEOUT_MS) {
        drainThread?.join(timeoutMs)
        audioThread?.join(timeoutMs)
    }

    fun finishMuxer() {
        if (muxerStarted) {
            try {
                muxer?.stop()
            } catch (_: IllegalStateException) {
            }
        }
        lifecycle.markFinalized()
    }

    fun setVideoBitrate(bitrateBps: Int) {
        videoCodec?.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps)
        })
    }

    fun release() {
        draining = false
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { preparedEncoderSurface?.release() } catch (_: Exception) {}
        preparedEncoderSurface = null
        encoderHandoff.markReleased()
        lifecycle.markReleased()
        audioRecord = null
        audioCodec = null
        videoCodec = null
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        drainThread = null
        audioThread = null
    }

    companion object {
        private const val AUDIO_MAX_INPUT_BYTES = 16_384
        private const val AUDIO_CHUNK_BYTES = 4_096
        private const val AUDIO_BYTES_PER_SAMPLE = 2
        private const val AUDIO_DEQUEUE_TIMEOUT_US = 10_000L
        private const val DRAIN_POLL_MS = 5L
        const val DRAIN_JOIN_TIMEOUT_MS = 2_000L
    }
}
