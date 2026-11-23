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
import com.vayunmathur.camera.platform.camera2.AudioEncoderPolicy
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
    val config: CustomRecordingConfig,
    private val initialBitrateBps: () -> Int,
    ownership: RecordingSessionOwnership = RecordingSessionOwnership.ENGINE_OWNED,
    /** Live input-device list for [config]'s preferred-device choice (engine-supplied). */
    private val audioInputDevices: () -> List<android.media.AudioDeviceInfo> = { emptyList() },
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

    /**
     * True when this recording will mux an audio track: audio was
     * requested AND an Opus encoder was created (see
     * [AudioEncoderPolicy]). False means video-only — either audio was
     * not requested, or no Opus encoder exists / the muxer rejected the
     * Opus track. Never true for AAC: there is no AAC path.
     */
    private var audioTrackExpected = false

    /** True once MediaMuxer has rejected the Opus track (dropped to video-only). */
    private var audioMuxerRejected = false

    /** True when the finished file will carry an Opus audio track. */
    val hasAudioTrack: Boolean get() = audioTrackExpected && !audioMuxerRejected

    // Per-track PTS bases: encoder output timestamps start at the
    // sensor/monotonic clock (a huge base), audio at zero. Both tracks
    // are rebased to their first sample so the muxed container starts
    // at ~0 and reports a true duration.
    private var videoBasePtsUs = -1L
    private var audioBasePtsUs = -1L

    /** Frames written on the slo-mo path (synthesized playback PTS source). */
    private var videoFrameIndex = 0L

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
                // The encoder is told the rate frames actually arrive
                // (capture rate for slo-mo): at the playback rate it
                // frame-drops the high-speed stream down to a fraction
                // of the captured frames (device-proven). Playback
                // timing is carried by the muxed PTS, not this field.
                setInteger(MediaFormat.KEY_FRAME_RATE, maxOf(config.fps, config.captureFps))
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

    /**
     * Creates the muxer and (when enabled) the Opus audio codec. Video
     * codec must already be configured.
     *
     * Opus is the only audio encoding (see [AudioEncoderPolicy]): when
     * no Opus encoder exists, or creating/configuring it fails, no audio
     * codec is created at all and the recording proceeds video-only with
     * a logged reason — never an AAC fallback.
     */
    fun setupMuxerAndAudio(outputFile: File) {
        if (videoCodec == null) configureVideoCodec()
        audioTrackExpected = false
        audioMuxerRejected = false
        if (config.audioEnabled) {
            val mime = AudioEncoderPolicy.resolveAudioMime(
                opusEncoderAvailable = CodecSupport.isOpusAudioEncoderAvailable,
            )
            if (mime == null) {
                Log.error(
                    "Camera2Recorder",
                    "No Opus audio encoder on this device; recording video-only (AAC is not an option)",
                )
            } else {
                try {
                    val audioFormat = MediaFormat.createAudioFormat(
                        mime, config.audioSampleRate, 1,
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrateBps)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_BYTES)
                    }
                    audioCodec = MediaCodec.createEncoderByType(mime).apply {
                        configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
                    audioTrackExpected = true
                } catch (e: Exception) {
                    // Opus encoder unusable on this device: record
                    // video-only rather than fall back to AAC.
                    Log.error(
                        "Camera2Recorder",
                        "Opus audio encoder unavailable; recording video-only",
                        e,
                    )
                    try { audioCodec?.release() } catch (_: Exception) {}
                    audioCodec = null
                }
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
                    if (isVideo) {
                        videoTrackIndex = m.addTrack(format)
                    } else if (!audioMuxerRejected) {
                        try {
                            audioTrackIndex = m.addTrack(format)
                        } catch (e: RuntimeException) {
                            // MediaMuxer cannot mux Opus into MP4 on
                            // this device/API level: drop the audio
                            // track and keep the video (never AAC).
                            Log.error(
                                "Camera2Recorder",
                                "MediaMuxer rejected the Opus track; recording video-only",
                                e,
                            )
                            audioMuxerRejected = true
                            audioTrackExpected = false
                        }
                    }
                    if (
                        !muxerStarted && MuxerStartPolicy.shouldStart(
                            hasVideoTrack = videoTrackIndex >= 0,
                            hasAudioTrack = audioTrackIndex >= 0,
                            audioEnabled = audioTrackExpected,
                        )
                    ) {
                        m.start()
                        muxerStarted = true
                    }
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index >= 0 -> {
                    val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
                    if (muxerStarted && info.size > 0 && trackIndex >= 0) {
                        val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: continue
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        // Rebase PTS to the track's first sample (see
                        // videoBasePtsUs): write with an adjusted copy,
                        // never the codec's raw monotonic timestamp.
                        val base = if (isVideo) {
                            if (videoBasePtsUs < 0) videoBasePtsUs = info.presentationTimeUs
                            videoBasePtsUs
                        } else {
                            if (audioBasePtsUs < 0) audioBasePtsUs = info.presentationTimeUs
                            audioBasePtsUs
                        }
                        val adjusted = MediaCodec.BufferInfo().apply {
                            set(
                                info.offset, info.size,
                                when {
                                    // Slo-mo: encoder PTS are unusable
                                    // (stale surface backlog, sensor
                                    // clock); synthesize playback timing
                                    // — each captured frame plays for
                                    // 1/playbackFps (see SloMoTimestampStretch).
                                    isVideo && config.captureFps > config.fps ->
                                        com.vayunmathur.camera.platform.camera2.SloMoTimestampStretch
                                            .playbackPtsUs(videoFrameIndex++, config.fps)
                                    isVideo -> maxOf(0L, info.presentationTimeUs - base)
                                    else -> maxOf(0L, info.presentationTimeUs - base)
                                },
                                info.flags,
                            )
                        }
                        muxer?.writeSampleData(trackIndex, buffer, adjusted)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /** AudioRecord (mono) -> Opus encoder input buffers, with codec timestamps. No-op without audio. */
    fun startAudio() {
        // No Opus encoder (or audio not requested): nothing to feed.
        if (!config.audioEnabled || !audioTrackExpected) return
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
            // Internal source is always CAMCORDER for video; the user's
            // choice is the physical device below, never this source type.
            MediaRecorder.AudioSource.CAMCORDER,
            config.audioSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, AUDIO_MAX_INPUT_BYTES),
        )
        audioRecord = record
        // Prefer the chosen physical mic (no explicit choice clears any
        // preference so the system routes). A disconnected/rejected device never fails the
        // recording: the seam swallows it and recording continues on the
        // system-routed input. If the device disconnects mid-recording,
        // Android reroutes the live AudioRecord itself; the read loop
        // below just keeps running.
        val applied = com.vayunmathur.camera.platform.camera2.PreferredAudioDeviceApplication.apply(
            selection = config.audioInputDevice,
            devices = runCatching { audioInputDevices() }.getOrDefault(emptyList()),
            idOf = { it.id },
            typeOf = { it.type },
            setPreferred = { device -> record.setPreferredDevice(device) },
            nameOf = { it.productName?.toString() },
            addressOf = { it.address },
            // No explicit/disc. choice: the platform's current routed input
            // for this record (a real device), if it is already known.
            routedDevice = { runCatching { record.routedDevice }.getOrNull() },
        )
        if (!applied.selection.isAuto && !applied.applied) {
            Log.status(
                "Camera2Recorder",
                "Preferred audio input unavailable; recording on the system-routed input",
            )
        }
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
        audioTrackExpected = false
        audioMuxerRejected = false
        videoBasePtsUs = -1L
        audioBasePtsUs = -1L
        videoFrameIndex = 0L
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
