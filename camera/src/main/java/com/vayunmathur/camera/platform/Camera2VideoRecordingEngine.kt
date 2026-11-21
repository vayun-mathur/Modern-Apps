package com.vayunmathur.camera.platform

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.vayunmathur.camera.domain.LiveBitrateController
import com.vayunmathur.camera.domain.RecordingPath
import com.vayunmathur.camera.domain.RecordingPathPolicy
import com.vayunmathur.camera.domain.SceneMotion
import com.vayunmathur.library.log.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Gate for the custom recording path. Default OFF: every recording uses the
 * CameraX Recorder until this path has been device-verified end to end
 * (see the verification note on [Camera2VideoRecordingEngine]).
 */
object CustomRecordingGate {
    const val ENABLED: Boolean = false
}

/** Selection inputs for the currently bound session, via [RecordingPathPolicy]. */
internal fun CameraViewModel.recordingPathFor(
    liveAdaptationWanted: Boolean,
    hlgBound: Boolean,
    stabilizationBound: Boolean,
): RecordingPath = RecordingPathPolicy.select(
    com.vayunmathur.camera.domain.RecordingPathInput(
        customPathEnabled = CustomRecordingGate.ENABLED,
        liveAdaptationWanted = liveAdaptationWanted,
        hlgRequested = hlgBound,
        stabilizationRequested = stabilizationBound,
        codecAv1Requested = recordingWithAv1,
        analysisBound = analysisStreamActiveMutable.value,
    )
)

/** Configuration for one custom-path recording. AVC/HEVC video + AAC audio only. */
data class CustomRecordingConfig(
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    val videoMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val audioSampleRate: Int = 48_000,
    val audioBitrateBps: Int = 128_000,
    val iFrameIntervalSec: Int = 1,
)

/** Read-back summary of a finished file (see [Camera2VideoRecordingEngine.validateOutput]). */
data class CustomRecordingValidation(
    val readable: Boolean,
    val trackCount: Int,
    val hasVideoTrack: Boolean,
    val durationUs: Long,
)

/**
 * Custom recording path: Camera2 capture session feeding a MediaCodec encoder
 * input surface (plus the caller's preview surface), AudioRecord feeding an
 * audio MediaCodec, and a MediaMuxer writing the MP4. It exists for the one
 * thing the CameraX Recorder cannot do: re-targeting the encoder mid-recording
 * via `MediaCodec.setParameters(KEY_BITRATE)`, driven here by a
 * [LiveBitrateController] over the same motion estimator as the CameraX path.
 *
 * Ownership contract: this recorder opens the camera device itself, so the
 * CameraX session must be torn down before [start] and rebuilt after [stop].
 * It supports SDR AVC/HEVC + AAC only — no HLG, no vendor stabilization/EIS
 * semantics, no AV1; [RecordingPathPolicy] keeps those recordings on CameraX.
 *
 * VERIFICATION STATUS: compile-checked and unit-tested at the policy/controller
 * level only. It has NOT been device-verified: before [CustomRecordingGate] is
 * ever flipped, a device run must record on this path and validate the output
 * with [validateOutput] (plus external ffprobe) — an unverified muxer/encoder
 * path must never be the only copy of a user's recording.
 */
class Camera2VideoRecordingEngine(
    private val context: Context,
    private val config: CustomRecordingConfig,
    private val controller: LiveBitrateController,
    private val motionSupplier: () -> SceneMotion?,
    private val onMotionSample: (com.vayunmathur.camera.domain.FrameDiffSample) -> Unit,
    private val onError: (Exception) -> Unit,
) : VideoRecordingEngine {
    override val path: RecordingPath = RecordingPath.CUSTOM_CAMERA2

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var analysisReader: android.media.ImageReader? = null
    private var ownedPreviewTexture: android.graphics.SurfaceTexture? = null
    private var ownedPreviewSurface: Surface? = null
    private var outcomeCallback: ((VideoRecordingOutcome) -> Unit)? = null
    private var previousGrid: FloatArray? = null
    private var previousGridWidth = 0
    private var lastAnalysisAtMs = 0L

    /** The engine-owned preview surface (for a TextureView to render), once started. */
    fun ownedPreviewSurface(): Surface? = ownedPreviewSurface

    override fun start(request: VideoRecordingRequest, onFinalized: (VideoRecordingOutcome) -> Unit) {
        val cameraId = request.cameraId
        if (cameraId == null) {
            onFinalized(
                VideoRecordingOutcome(
                    request.outputFile,
                    IllegalArgumentException("Camera2 engine requires a cameraId")
                )
            )
            return
        }
        outcomeCallback = onFinalized
        start(request.outputFile, cameraId, request.previewSurface)
    }

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null

    @Volatile private var muxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    @Volatile private var running = false
    private var drainThread: Thread? = null
    private var audioThread: Thread? = null
    private var adaptationThread: Thread? = null
    private var outputFile: File? = null

    /** Opens the camera + encoders and starts recording into [outputFile]. */
    @SuppressLint("MissingPermission")
    fun start(outputFile: File, cameraId: String, previewSurface: Surface?) {
        check(!running) { "Camera2VideoRecordingEngine already running" }
        this.outputFile = outputFile
        try {
            setupEncoders(outputFile)
            openCamera(cameraId, previewSurface)
            running = true
            startDrainThread()
            startAudioThread()
            startAdaptationThread()
        } catch (e: Exception) {
            releaseAll()
            throw e
        }
    }

    /** Stops capture, drains the encoders, and finishes the muxed file. */
    override fun stop() {
        if (!running) return
        running = false
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            cameraDevice?.close()
            try {
                videoCodec?.signalEndOfInputStream()
            } catch (_: IllegalStateException) {
            }
            audioRecord?.stop()
            drainThread?.join(DRAIN_JOIN_TIMEOUT_MS)
            audioThread?.join(DRAIN_JOIN_TIMEOUT_MS)
            adaptationThread?.interrupt()
            if (muxerStarted) muxer?.stop()
        } catch (e: IllegalStateException) {
            Log.error("Camera2Recorder", "stop() failed", e)
        } finally {
            releaseAll()
            // Never hand back a corrupt file: read it back, and on failure
            // delete it and report an error outcome instead of an output.
            val file = outputFile
            val callback = outcomeCallback
            outcomeCallback = null
            if (file != null && callback != null) {
                val validation = validateOutput(file)
                if (validation.readable) {
                    callback(VideoRecordingOutcome(file))
                } else {
                    file.delete()
                    callback(
                        VideoRecordingOutcome(
                            file,
                            IllegalStateException(
                                "Custom-path output failed read-back validation: $validation"
                            )
                        )
                    )
                }
            }
        }
    }

    private fun setupEncoders(outputFile: File) {
        val videoFormat = MediaFormat.createVideoFormat(config.videoMime, config.width, config.height)
            .apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, controller.currentBitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec)
            }
        videoCodec = MediaCodec.createEncoderByType(config.videoMime).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, config.audioSampleRate, 1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_BYTES)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String, previewSurface: Surface?) {
        cameraThread = HandlerThread("Camera2RecorderCamera").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        val manager = context.getSystemService(CameraManager::class.java)
        val encoderSurface = videoCodec!!.createInputSurface()
        videoCodec!!.start()
        // Preview surface: the caller's when given, else an engine-owned
        // SurfaceTexture surface (exposed via ownedPreviewSurface() for a
        // TextureView to render once the UI migrates off CameraX preview).
        val effectivePreview = previewSurface ?: run {
            val texture = android.graphics.SurfaceTexture(PREVIEW_TEXTURE_ID).apply {
                setDefaultBufferSize(config.width, config.height)
            }
            ownedPreviewTexture = texture
            Surface(texture).also { ownedPreviewSurface = it }
        }
        // Analysis surface: same motion signal as the CameraX path, from an
        // ImageReader feeding the shared estimator through onMotionSample.
        val reader = android.media.ImageReader.newInstance(
            ANALYSIS_WIDTH, ANALYSIS_HEIGHT, android.graphics.ImageFormat.YUV_420_888, ANALYSIS_MAX_IMAGES
        )
        analysisReader = reader
        reader.setOnImageAvailableListener({ r ->
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (nowMs - lastAnalysisAtMs < VIDEO_MOTION_SAMPLE_INTERVAL_MS) {
                runCatching { r.acquireLatestImage()?.close() }
                return@setOnImageAvailableListener
            }
            lastAnalysisAtMs = nowMs
            val image = try {
                r.acquireLatestImage()
            } catch (_: IllegalStateException) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val (grid, gridWidth) = sampleYPlaneGrid(
                    plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height
                ) ?: return@setOnImageAvailableListener
                val previous = previousGrid
                previousGrid = grid
                if (previous != null && previous.size == grid.size && previousGridWidth == gridWidth) {
                    com.vayunmathur.camera.domain.SceneMotionMath
                        .sample(previous, grid, gridWidth)
                        ?.let { onMotionSample(it) }
                }
                previousGridWidth = gridWidth
            } finally {
                image.close()
            }
        }, cameraHandler)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                try {
                    val surfaces = listOfNotNull(encoderSurface, effectivePreview, reader.surface)
                    device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(encoderSurface)
                                addTarget(effectivePreview)
                                addTarget(reader.surface)
                                set(
                                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    android.util.Range(config.fps, config.fps)
                                )
                            }.build()
                            try {
                                session.setRepeatingRequest(request, null, cameraHandler)
                            } catch (e: IllegalStateException) {
                                onError(e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            onError(IllegalStateException("Custom recorder capture session failed to configure"))
                        }
                    }, cameraHandler)
                } catch (e: IllegalStateException) {
                    onError(e)
                } catch (e: IllegalArgumentException) {
                    onError(e)
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                cameraDevice = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                cameraDevice = null
                onError(IllegalStateException("Custom recorder camera error $error"))
            }
        }, cameraHandler)
        // Facing check kept explicit: callers pick the id, but a front camera
        // would need mirroring this path does not do.
        val facing = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.LENS_FACING)
        Log.debug("Camera2Recorder", "opening camera $cameraId facing=$facing")
    }

    /** Muxer drain: video + audio codec outputs, started once both formats are known. */
    private fun startDrainThread() {
        drainThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            while (running || muxerStarted) {
                drainCodec(videoCodec, isVideo = true, bufferInfo)
                drainCodec(audioCodec, isVideo = false, bufferInfo)
                if (!running && drainedBoth()) break
                Thread.sleep(DRAIN_POLL_MS)
            }
        }, "Camera2RecorderDrain").apply { start() }
    }

    private fun drainedBoth(): Boolean = !running // simplified end condition; see stop()

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
                    if (videoTrackIndex >= 0 && audioTrackIndex >= 0 && !muxerStarted) {
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

    /** AudioRecord (mono) -> audio encoder input buffers, with codec timestamps. */
    private fun startAudioThread() {
        val minBuffer = AudioRecord.getMinBufferSize(
            config.audioSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
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
            maxOf(minBuffer, AUDIO_MAX_INPUT_BYTES)
        )
        audioRecord = record
        record.startRecording()
        audioThread = Thread({
            val chunk = ByteArray(AUDIO_CHUNK_BYTES)
            var presentationUs = 0L
            val codec = audioCodec ?: return@Thread
            while (running) {
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
                val flags = if (!running) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                codec.queueInputBuffer(inputIndex, 0, minOf(read, AUDIO_CHUNK_BYTES), presentationUs, flags)
                presentationUs += read.toLong() * 1_000_000L / (config.audioSampleRate * AUDIO_BYTES_PER_SAMPLE)
            }
        }, "Camera2RecorderAudio").apply { start() }
    }

    /** Every 2 s: ask the controller, apply via encoder parameters when it moves. */
    private fun startAdaptationThread() {
        adaptationThread = Thread({
            var tickMs = 0L
            while (running) {
                Thread.sleep(LiveBitrateController.DEFAULT_EVALUATE_INTERVAL_MS)
                tickMs += LiveBitrateController.DEFAULT_EVALUATE_INTERVAL_MS
                val target = controller.evaluate(motionSupplier(), tickMs) ?: continue
                try {
                    videoCodec?.setParameters(Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target)
                    })
                    Log.debug("Camera2Recorder", "live bitrate -> $target")
                } catch (e: IllegalStateException) {
                    Log.error("Camera2Recorder", "setParameters(KEY_BITRATE) failed", e)
                }
            }
        }, "Camera2RecorderAdapt").apply { start() }
    }

    private fun releaseAll() {
        try { analysisReader?.close() } catch (_: Exception) {}
        try { ownedPreviewSurface?.release() } catch (_: Exception) {}
        try { ownedPreviewTexture?.release() } catch (_: Exception) {}
        analysisReader = null
        ownedPreviewSurface = null
        ownedPreviewTexture = null
        previousGrid = null
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { cameraThread?.quitSafely() } catch (_: Exception) {}
        audioRecord = null
        audioCodec = null
        videoCodec = null
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
    }

    companion object {
        private const val PREVIEW_TEXTURE_ID = 1
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
        private const val ANALYSIS_MAX_IMAGES = 3
        private const val AUDIO_MAX_INPUT_BYTES = 16_384
        private const val AUDIO_CHUNK_BYTES = 4_096
        private const val AUDIO_BYTES_PER_SAMPLE = 2 // PCM16 mono
        private const val AUDIO_DEQUEUE_TIMEOUT_US = 10_000L
        private const val DRAIN_POLL_MS = 5L
        private const val DRAIN_JOIN_TIMEOUT_MS = 2_000L

        /**
         * Read-back validation for a finished custom-path file: the extractor
         * must open it, find a video track, and report a positive duration.
         * Device-only (android.media is stubbed in unit tests) — callers run
         * this after [stop] before treating the file as the user's recording.
         */
        fun validateOutput(file: File): CustomRecordingValidation {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.absolutePath)
                var hasVideo = false
                var durationUs = 0L
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) hasVideo = true
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                    }
                }
                CustomRecordingValidation(
                    readable = extractor.trackCount > 0 && hasVideo && durationUs > 0,
                    trackCount = extractor.trackCount,
                    hasVideoTrack = hasVideo,
                    durationUs = durationUs,
                )
            } catch (e: Exception) {
                Log.error("Camera2Recorder", "validateOutput failed for ${file.name}", e)
                CustomRecordingValidation(false, 0, false, 0L)
            } finally {
                extractor.release()
            }
        }
    }
}
