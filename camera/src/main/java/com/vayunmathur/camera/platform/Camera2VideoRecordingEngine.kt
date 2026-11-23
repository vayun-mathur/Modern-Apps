package com.vayunmathur.camera.platform

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.vayunmathur.camera.domain.LiveBitrateController
import com.vayunmathur.camera.domain.SceneMotion
import com.vayunmathur.camera.platform.camera2.RecordingSessionOwnership
import com.vayunmathur.library.log.Log
import java.io.File

/** Configuration for one custom-path recording. AVC/HEVC/AV1 video + Opus audio only. */
data class CustomRecordingConfig(
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    /**
     * Rate frames are captured at, when it differs from the playback
     * [fps] (Phase-5 slo-mo: 120/240 captured, 30 played back). Equal to
     * [fps] for normal recording; the encoder is then configured with
     * `KEY_CAPTURE_RATE = captureFps`, `KEY_FRAME_RATE = fps`.
     */
    val captureFps: Int = fps,
    val videoMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    // Audio is always Opus (AudioEncoderPolicy.OPUS_MIME): there is no
    // audio-mime field here because there is no second option to select,
    // and a device without an Opus encoder records video-only instead.
    val audioSampleRate: Int = 48_000,
    val audioBitrateBps: Int = 128_000,
    val iFrameIntervalSec: Int = 1,
    /**
     * False records video only (slo-mo's contract, SloMoEnginePolicy
     * piece 5): no AudioRecord/Opus codec is created and the muxer starts
     * on the video track alone ([MuxerStartPolicy]). Also the effective
     * state when no Opus encoder exists (see [AudioEncoderPolicy]).
     */
    val audioEnabled: Boolean = true,
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
 * It supports SDR AVC/HEVC + Opus only — no HLG, no vendor stabilization/EIS
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

    /** The engine-owned preview texture a TextureView adopts while recording. */
    fun ownedPreviewTexture(): android.graphics.SurfaceTexture? = ownedPreviewTexture

    @Volatile private var previewTextureBorrowed = false

    /** Marks the owned preview texture as adopted by a TextureView (it releases it). */
    fun markPreviewTextureBorrowed() {
        previewTextureBorrowed = true
    }

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

    /** Shared codec/muxer/drain core (piece 2); this engine owns the session, the core does not. */
    private val core = Camera2RecordingCore(
        config,
        initialBitrateBps = { controller.currentBitrateBps },
        ownership = RecordingSessionOwnership.ENGINE_OWNED,
    )

    @Volatile private var running = false
    private var adaptationThread: Thread? = null
    private var outputFile: File? = null

    /** Opens the camera + encoders and starts recording into [outputFile]. */
    @SuppressLint("MissingPermission")
    fun start(outputFile: File, cameraId: String, previewSurface: Surface?) {
        check(!running) { "Camera2VideoRecordingEngine already running" }
        this.outputFile = outputFile
        try {
            core.setupMuxerAndAudio(outputFile)
            openCamera(cameraId, previewSurface)
            running = true
            core.startDrain()
            core.startAudio()
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
            core.signalVideoEos()
            core.stopAudio()
            core.joinDrainAndAudio()
            adaptationThread?.interrupt()
            core.finishMuxer()
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

    /**
     * Codec-first split (SloMoEnginePolicy piece 1), delegated to the
     * shared core: configures the video codec and exposes its input
     * surface *before* any camera is opened, so a caller-owned session
     * (e.g. the constrained high-speed session) can list the surface as a
     * configure-time target. Idempotent; [start] reuses the prepared
     * surface instead of creating one in `openCamera`.
     */
    fun prepareEncoderSurface(): Surface? = core.prepareEncoderSurface()

    /** The surface exposed by [prepareEncoderSurface], or null before it. */
    fun preparedEncoderSurface(): Surface? = core.preparedEncoderSurface()

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String, previewSurface: Surface?) {
        cameraThread = HandlerThread("Camera2RecorderCamera").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        val manager = context.getSystemService(CameraManager::class.java)
        val encoderSurface = core.preparedEncoderSurface() ?: prepareEncoderSurface()
            ?: throw IllegalStateException("encoder input surface unavailable")
        core.startVideoCodec()
        // Preview surface: the caller's when given, else an engine-owned
        // SurfaceTexture surface (exposed via ownedPreviewSurface() for a
        // TextureView to render once the UI migrates off CameraX preview).
        val effectivePreview = previewSurface ?: run {
            val texture = android.graphics.SurfaceTexture(PREVIEW_TEXTURE_ID).apply {
                // Preview never needs encoder resolution: cap at 1080p so
                // the (encoder + preview + analysis) stream combination
                // stays within guaranteed HAL limits at UHD tiers.
                setDefaultBufferSize(
                    minOf(config.width, PREVIEW_MAX_WIDTH),
                    minOf(config.height, PREVIEW_MAX_HEIGHT),
                )
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
                            core.markSessionStreaming()
                            try {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(encoderSurface)
                                    addTarget(effectivePreview)
                                    addTarget(reader.surface)
                                    set(
                                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                        android.util.Range(config.captureFps, config.captureFps)
                                    )
                                }.build()
                                session.setRepeatingRequest(request, null, cameraHandler)
                            } catch (e: IllegalStateException) {
                                // Device closed under us (a competing
                                // session took the camera): surface as an
                                // engine error, never an uncaught crash.
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

    /** Every 2 s: ask the controller, apply via encoder parameters when it moves. */
    private fun startAdaptationThread() {
        adaptationThread = Thread({
            var tickMs = 0L
            while (running) {
                try {
                    Thread.sleep(LiveBitrateController.DEFAULT_EVALUATE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    // stop() interrupts to end the loop: exit quietly,
                    // never as an uncaught exception.
                    break
                }
                tickMs += LiveBitrateController.DEFAULT_EVALUATE_INTERVAL_MS
                val target = controller.evaluate(motionSupplier(), tickMs) ?: continue
                try {
                    core.setVideoBitrate(target)
                    Log.debug("Camera2Recorder", "live bitrate -> $target")
                } catch (e: IllegalStateException) {
                    Log.error("Camera2Recorder", "setParameters(KEY_BITRATE) failed", e)
                }
            }
        }, "Camera2RecorderAdapt").apply { start() }
    }

    private fun releaseAll() {
        try { analysisReader?.close() } catch (_: Exception) {}
        // A borrowed texture/surface pair is owned by the adopting
        // TextureView, which releases it on detach; releasing here too
        // would race the view still rendering the last frame.
        if (!previewTextureBorrowed) {
            try { ownedPreviewSurface?.release() } catch (_: Exception) {}
            try { ownedPreviewTexture?.release() } catch (_: Exception) {}
        }
        previewTextureBorrowed = false
        analysisReader = null
        ownedPreviewSurface = null
        ownedPreviewTexture = null
        previousGrid = null
        core.release()
        try { cameraThread?.quitSafely() } catch (_: Exception) {}
    }

    companion object {
        private const val PREVIEW_TEXTURE_ID = 1
        private const val PREVIEW_MAX_WIDTH = 1920
        private const val PREVIEW_MAX_HEIGHT = 1080
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
        private const val ANALYSIS_MAX_IMAGES = 3

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
