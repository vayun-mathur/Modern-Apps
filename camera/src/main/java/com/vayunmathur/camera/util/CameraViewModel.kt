package com.vayunmathur.camera.util

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.camera.core.CameraSelector
import com.vayunmathur.camera.domain.MotionSaveQueue
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.math.atan2

/**
 * State and session owner for the single camera screen: the bound CameraX session, every
 * capture setting, and the capture paths (implemented as extensions in the sibling files).
 */
class CameraViewModel(internal val app: Application) : AndroidViewModel(app) {
    /** Tuning constants and lookup tables shared by the extension files. */
    companion object {
        // Companions are public so same-module extension files resolve these constants.

        // Once the vendor NIGHT extension fails to bind on this device, don't try again for this
        // long (persisted). Re-probes after it expires in case a system update fixes the extender.
        const val NIGHT_EXT_FAILURE_TTL_MS = 7L * 24 * 60 * 60 * 1000

        // Night-mode auto-detection tuning. Engage once average Y stays below ENGAGE for a few
        // frames; disengage once it climbs above DISENGAGE for a few frames. The gap between the
        // two thresholds is the hysteresis band that stops the moon button from flickering.
        const val NIGHT_ENGAGE_LUMA = 40f
        const val NIGHT_DISENGAGE_LUMA = 55f
        const val NIGHT_DEBOUNCE_FRAMES = 4

        // Still-capture crop ratios in portrait-UI orientation (width:height).
        const val ASPECT_SQUARE_W = 1
        const val ASPECT_SQUARE_H = 1
        const val ASPECT_4_3_W = 3
        const val ASPECT_4_3_H = 4
        const val ASPECT_3_2_W = 2
        const val ASPECT_3_2_H = 3
        const val ASPECT_16_9_W = 9
        const val ASPECT_16_9_H = 16

        // Target night exposure/ISO used when night mode fires on an Auto exposure stop. The
        // single-frame emulation (fallback) uses the long ~1/4s target; the multi-frame burst uses
        // a shorter per-frame exposure so each frame has less motion blur and the merge recovers SNR.
        const val NIGHT_TARGET_EXPOSURE_NANOS = 250_000_000L // ~1/4s
        const val NIGHT_BURST_PER_FRAME_NANOS = 100_000_000L // ~1/10s, in the 1/15–1/8s range
        const val NIGHT_ISO_FRACTION = 0.75f

        /** Safety cap on frames captured during a single press-and-hold burst. */
        const val BURST_MAX = 30

        // Motion-Photo ring buffer: the window comes from the user's duration setting
        // (issue #712); analysis frames are ~1.2 MP ARGB (~5 MB each), so the count cap
        // scales with the chosen duration but is hard-capped to bound memory.
        const val MOTION_FRAMES_PER_SECOND = 8
        const val MOTION_MAX_FRAMES_HARD_CAP = 24

        // Motion Photos still building after their shutter was released (#914). Each holds its
        // drained frames (up to MOTION_MAX_FRAMES_HARD_CAP bitmaps) until encoded, so keep this
        // small: one encoding plus one waiting. A press beyond it takes a plain still.
        const val MAX_PENDING_MOTION_SAVES = 2

        val EXPOSURE_TIME_STOPS = listOf(
            ExposureTimeStop("Auto", null),
            ExposureTimeStop("1/4000s", 250_000L),
            ExposureTimeStop("1/2000s", 500_000L),
            ExposureTimeStop("1/1000s", 1_000_000L),
            ExposureTimeStop("1/500s", 2_000_000L),
            ExposureTimeStop("1/250s", 4_000_000L),
            ExposureTimeStop("1/125s", 8_000_000L),
            ExposureTimeStop("1/60s", 16_666_667L),
            ExposureTimeStop("1/30s", 33_333_333L),
            ExposureTimeStop("1/15s", 66_666_667L),
            ExposureTimeStop("1/8s", 125_000_000L),
            ExposureTimeStop("1/4s", 250_000_000L),
            ExposureTimeStop("1/2s", 500_000_000L),
            ExposureTimeStop("1s", 1_000_000_000L),
            ExposureTimeStop("2s", 2_000_000_000L),
            ExposureTimeStop("4s", 4_000_000_000L),
        )

        /**
         * EXIF tags copied from the original frame onto an adjusted capture. Orientation and GPS
         * are set separately (see writeCaptureExif); dimension tags are omitted so they aren't
         * left inconsistent with the re-encoded JPEG.
         */
        val EXIF_TAGS_TO_COPY = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        )
    }

    internal val ds = DataStoreUtils.getInstance(app)

    internal val cameraModeMutable = MutableStateFlow(CameraMode.PHOTO)
    val cameraMode = cameraModeMutable.asStateFlow()

    internal val lensFacingMutable = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing = lensFacingMutable.asStateFlow()

    internal val availableLensesMutable =
        MutableStateFlow<List<com.vayunmathur.camera.domain.PhysicalLens>>(emptyList())
    val availableLenses = availableLensesMutable.asStateFlow()

    internal val selectedLensMutable = MutableStateFlow<com.vayunmathur.camera.domain.PhysicalLens?>(null)
    val selectedLens = selectedLensMutable.asStateFlow()

    internal val lensCapabilitiesMutable =
        MutableStateFlow<com.vayunmathur.camera.platform.LensCapabilities?>(null)
    val lensCapabilities = lensCapabilitiesMutable.asStateFlow()

    internal val hasFlashUnitMutable = MutableStateFlow(true)
    val hasFlashUnit = hasFlashUnitMutable.asStateFlow()

    internal val exposureCompRangeMutable = MutableStateFlow<ClosedRange<Float>?>(null)
    val exposureCompRange = exposureCompRangeMutable.asStateFlow()

    internal val flashModeMutable = MutableStateFlow(FlashMode.OFF)
    val flashMode = flashModeMutable.asStateFlow()

    internal val torchEnabledMutable = MutableStateFlow(false)
    val torchEnabled = torchEnabledMutable.asStateFlow()

    internal val timerDurationMutable = MutableStateFlow(TimerDuration.NONE)
    val timerDuration = timerDurationMutable.asStateFlow()

    internal val aspectRatioMutable = MutableStateFlow(AspectRatioOption.RATIO_4_3)
    val aspectRatio = aspectRatioMutable.asStateFlow()

    internal val videoCodecMutable = MutableStateFlow(CodecSupport.defaultVideoCodec)
    val videoCodec = videoCodecMutable.asStateFlow()

    // Motion-Photo clip length (issue #712). Default 3s ≈ the old ~1.5s-12-frame buffer.
    internal val motionPhotoDurationMutable = MutableStateFlow(MotionPhotoDuration.THREE)
    val motionPhotoDuration = motionPhotoDurationMutable.asStateFlow()

    internal val audioInputSourceMutable = MutableStateFlow(AudioInputSource.CAMCORDER)
    val audioInputSource = audioInputSourceMutable.asStateFlow()

    internal val saveTargetMutable = MutableStateFlow<SaveTarget>(SaveTarget.MediaStoreDefault)
    val saveTarget = saveTargetMutable.asStateFlow()

    internal val isRecordingMutable = MutableStateFlow(false)
    val isRecording = isRecordingMutable.asStateFlow()

    internal val recordingDurationSecMutable = MutableStateFlow(0L)
    val recordingDurationSec = recordingDurationSecMutable.asStateFlow()
    internal var recordingTimerJob: kotlinx.coroutines.Job? = null

    internal val timerCountdownMutable = MutableStateFlow(0)
    val timerCountdown = timerCountdownMutable.asStateFlow()
    internal var timerCountdownJob: kotlinx.coroutines.Job? = null

    internal val qrResultMutable = MutableStateFlow<String?>(null)
    val qrResult = qrResultMutable.asStateFlow()

    internal val zoomRatioMutable = MutableStateFlow(1f)
    val zoomRatio = zoomRatioMutable.asStateFlow()

    internal val availableZoomLevelsMutable = MutableStateFlow(listOf("1x" to 1f))
    val availableZoomLevels = availableZoomLevelsMutable.asStateFlow()

    // Whether the front-camera preview and saved selfie are horizontally mirrored (issue #632).
    // Default true preserves the long-standing mirror-like selfie behavior.
    internal val mirrorFrontMutable = MutableStateFlow(true)
    val mirrorFront = mirrorFrontMutable.asStateFlow()

    // Post-processing toggles (issue #662). Default true preserves the stock pipeline
    // behavior; false pushes NOISE_REDUCTION_MODE_OFF / EDGE_MODE_OFF live.
    internal val noiseReductionEnabledMutable = MutableStateFlow(true)
    val noiseReductionEnabled = noiseReductionEnabledMutable.asStateFlow()

    internal val edgeSharpeningEnabledMutable = MutableStateFlow(true)
    val edgeSharpeningEnabled = edgeSharpeningEnabledMutable.asStateFlow()

    internal val isCapturingMutable = MutableStateFlow(false)
    val isCapturing = isCapturingMutable.asStateFlow()

    // Fires when the sensor starts exposing a still (CameraX onCaptureStarted), so the viewfinder
    // can flash at the real moment of capture instead of when the file lands (#914).
    internal val shutterFeedbackMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shutterFeedback = shutterFeedbackMutable.asSharedFlow()

    // Background Motion Photo saves (#914): the clip is built after the shutter is released.
    // The scope is deliberately not cancelled in onCleared, so a save in progress still lands;
    // the dispatcher and saver are vars only so tests can drive the state machine.
    internal val motionSaveQueue = MotionSaveQueue(MAX_PENDING_MOTION_SAVES)
    internal val motionSaveMutex = Mutex()
    internal val motionSaveScope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.error("CameraViewModel", "Background Motion Photo save failed", e)
        }
    )
    internal var motionSaveDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var motionPhotoSaver: suspend (MotionSaveRequest) -> Uri? = { assembleAndSaveMotionPhoto(it) }

    internal val locationEnabledMutable = MutableStateFlow(false)
    val locationEnabled = locationEnabledMutable.asStateFlow()

    internal val lastCaptureUriMutable = MutableStateFlow<Uri?>(null)
    val lastCaptureUri = lastCaptureUriMutable.asStateFlow()

    private val _galleryThumbnail = MutableStateFlow<Bitmap?>(null)
    val galleryThumbnail = _galleryThumbnail.asStateFlow()

    internal val gridEnabledMutable = MutableStateFlow(false)
    val gridEnabled = gridEnabledMutable.asStateFlow()

    // Horizon level indicator: a device-roll angle (degrees) read off the accelerometer/gravity
    // sensor, exposed only while the level overlay is enabled. Registered lazily so the sensor
    // isn't running when the overlay is off.
    internal val levelEnabledMutable = MutableStateFlow(false)
    val levelEnabled = levelEnabledMutable.asStateFlow()

    private val _roll = MutableStateFlow(0f)
    val roll = _roll.asStateFlow()

    internal val sensorManager by lazy { app.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    // Dedicated single thread for portrait segmentation so main thread stays free for preview rendering.
    internal val bokehExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Same for the photo stream. [PhotoAnalyzer] copies the Y plane, runs a ZXing decode and (in
    // PHOTO) a toBitmap() per frame; on the main executor that only ran between UI frames, so
    // KEEP_ONLY_LATEST dropped most of them and QR codes decoded only when main happened to be idle.
    internal val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Bakes the same bokeh into the saved still. Separate from the preview's BokehAnalyzer, which is
    // owned by the composable and torn down with it; this one loads its model on the first capture.
    internal val stillBokeh = StillBokehRenderer(app)
    internal val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            // 0° when the device is held upright in portrait; positive as the top edge tilts right.
            _roll.value = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    internal var levelSensorRegistered = false

    // Portrait blur strength (0..1 UI → ~0.4..1.8 shader blurScale). Scales the bokeh shader's
    // tap offsets so the user can dial the background blur up/down. Default 0.5 ≈ 1.0 blurScale.
    internal val blurStrengthMutable = MutableStateFlow(0.5f)
    val blurStrength = blurStrengthMutable.asStateFlow()

    internal val exposureCompensationMutable = MutableStateFlow(0f)
    val exposureCompensation = exposureCompensationMutable.asStateFlow()

    internal val warmthMutable = MutableStateFlow(0f)
    val warmth = warmthMutable.asStateFlow()

    internal val shadowsMutable = MutableStateFlow(0f)
    val shadows = shadowsMutable.asStateFlow()

    internal val exposureTimeIndexMutable = MutableStateFlow(0)
    val exposureTimeIndex = exposureTimeIndexMutable.asStateFlow()

    // --- Manual pro controls (ISO). Index 0 == Auto. ---

    // ISO: index 0 == Auto; otherwise an index into [isoStopsMutable] (+1). Stops are derived from the
    // sensor's SENSOR_INFO_SENSITIVITY_RANGE when the session binds.
    internal val manualIsoIndexMutable = MutableStateFlow(0)
    val manualIsoIndex = manualIsoIndexMutable.asStateFlow()

    internal val isoStopsMutable = MutableStateFlow<List<Int>>(emptyList())
    val isoStops = isoStopsMutable.asStateFlow()

    // Last auto-converged AE ISO / exposure, snapshotted off the preview's capture results so a
    // half-manual exposure (only ISO or only shutter set) can seed the other from the auto value.
    @Volatile internal var lastAeIso: Int? = null
    @Volatile internal var lastAeExposureNanos: Long? = null

    internal val aeSnapshotCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: android.hardware.camera2.CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeIso = it }
            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeExposureNanos = it }
        }
    }

    // Night mode is fully automatic: brightness detection engages it, and the moon button lets
    // the user override it off for the current dark scene. nightModeActive drives the capture path.
    internal val lowLightDetectedMutable = MutableStateFlow(false)
    val lowLightDetected = lowLightDetectedMutable.asStateFlow()

    internal val nightModeOverriddenOffMutable = MutableStateFlow(false)
    val nightModeOverriddenOff = nightModeOverriddenOffMutable.asStateFlow()

    val nightModeActive = combine(lowLightDetectedMutable, nightModeOverriddenOffMutable) { low, off -> low && !off }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Whether to offer the vendor NIGHT extension. We optimistically probe with the
    // isSessionConfigSupported check, but the authoritative signal is the actual bind: if
    // the night preview session ever fails to bind on this device, we remember that (persisted, with a
    // ~1-week TTL) and stop offering night for a week — so a broken extender (GrapheneOS/Pixel)
    // makes at most one failed attempt per week instead of engaging/failing in a loop.
    internal val nightExtensionUsableMutable = MutableStateFlow(false)
    val nightExtensionUsable = nightExtensionUsableMutable.asStateFlow()

    // Night-mode detection lives in CameraNightMode.kt as extensions.
    // Debounce counters for the luminance hysteresis filter (accessed from those extensions).
    @Volatile internal var lowLumaFrames = 0
    @Volatile internal var highLumaFrames = 0

    // Preferred night detection: CameraX's getNightModeIndicator() (1.7.0-alpha02) — the OS/vendor
    // reports when the scene is dark enough that night mode is RECOMMENDED. We observe the bound
    // camera's indicator LiveData and drive lowLightDetectedMutable from it. Where the device doesn't
    // support the indicator, onLuminance()'s luminance heuristic is used as a fallback instead.
    internal var nightIndicatorLiveData: androidx.lifecycle.LiveData<Int>? = null
    var nightIndicatorSupported = false
        internal set
    internal val nightIndicatorObserver = androidx.lifecycle.Observer<Int> { state ->
        // Don't override a manual "off" for the current dark scene; the moon button owns that.
        if (nightModeOverriddenOffMutable.value) return@Observer
        // RECOMMENDED engages; anything else (NOT_RECOMMENDED / UNKNOWN) disengages, so night turns
        // off again when the scene brightens. (The toggle loop this used to cause on unusable devices
        // is now prevented by the daily failure cache in nightExtensionUsable instead.)
        lowLightDetectedMutable.value = state == androidx.camera.core.NightModeIndicator.RECOMMENDED
    }

    // Live NIGHT-extension processing strength (0..100) from CameraExtensionsInfo.getExtensionStrength(),
    // for a "night processing" indicator in the UI. Null when unavailable / not in an extension session.
    internal val extensionStrengthMutable = MutableStateFlow<Int?>(null)
    val extensionStrength = extensionStrengthMutable.asStateFlow()

    // The vendor NIGHT extension can bind "successfully" (bindToLifecycle returns) yet fail
    // asynchronously when camera-pipe configures the ExtensionCaptureSession (GrapheneOS/Pixel:
    // ERROR_STREAM_CONFIG). That never throws from setupNightPreviewSession, so we watch the bound
    // extension camera's CameraState and treat any error as a real failure → cache it for a week.
    internal var extensionStrengthLiveData: androidx.lifecycle.LiveData<Int>? = null
    internal val extensionStrengthObserver =
        androidx.lifecycle.Observer<Int> { s -> extensionStrengthMutable.value = s }
    internal var extensionCameraStateLiveData: androidx.lifecycle.LiveData<androidx.camera.core.CameraState>? = null
    internal val extensionCameraStateObserver = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { st ->
        val err = st.error ?: return@Observer
        Log.status(
            "NightPreview",
            "night extension camera error type=${st.type} code=${err.code} – " +
                "disabling night extension (cached)"
        )
        recordNightExtensionFailure()
    }

    internal val longExposureProgressMutable = MutableStateFlow(0f)
    val longExposureProgress = longExposureProgressMutable.asStateFlow()

    internal val longExposureRemainingMutable = MutableStateFlow("")
    val longExposureRemaining = longExposureRemainingMutable.asStateFlow()

    internal var longExposureTimerJob: kotlinx.coroutines.Job? = null

    internal var lastLocation: Location? = null
    internal var currentRecording: Recording? = null
    internal var sloMoFps: Int = 30

    // IMAGE_CAPTURE (system "take a photo") intent state. When capturing for a caller, we either
    // write the full-res JPEG to their EXTRA_OUTPUT Uri, or hand back a downscaled thumbnail.
    var captureForResult: Boolean = false
        private set
    internal var resultOutputUri: Uri? = null

    /** Enters capture-for-result mode; [outputUri] is the caller's EXTRA_OUTPUT (may be null). */
    fun enableCaptureForResult(outputUri: Uri?) {
        captureForResult = true
        resultOutputUri = outputUri
    }

    internal val panoramaEngine = PanoramaEngine(app)

    // Guards panorama stitching so a second stop request can't start a concurrent stitch.
    // Written from CameraVideoRecording.kt's finishPanoramaSweep() extension.
    @Volatile internal var isFinishingPano = false

    // Unified manual session state (all modes bind through one CameraXViewfinder).
    internal val surfaceRequestMutable = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest = surfaceRequestMutable.asStateFlow()

    internal var cameraProvider: ProcessCameraProvider? = null
    // Cached per ProcessCameraProvider instance; ExtensionsManager must be tied to the same provider.
    internal var extensionsManager: ExtensionsManager? = null
    internal var sessionLifecycleOwner: ManualLifecycleOwner? = null
    internal var boundCamera: Camera? = null
    internal var imageCapture: ImageCapture? = null
    internal var imageAnalysis: ImageAnalysis? = null

    // The analyzer the UI wants running, kept across teardown/rebind. Every session bind re-attaches
    // it via [attachDesiredAnalyzer], so a new ImageAnalysis is never left bare: the UI's attach
    // effect is keyed on photoSessionActive, and that StateFlow conflates, so a false->true rebind
    // inside one frame used to leave the analyzer detached until the next mode switch.
    internal var desiredAnalyzer: ImageAnalysis.Analyzer? = null
    internal var desiredAnalyzerExecutor: Executor? = null

    /** True once the photo session's use cases (incl. ImageAnalysis) are bound. */
    internal val photoSessionActiveMutable = MutableStateFlow(false)
    val photoSessionActive = photoSessionActiveMutable.asStateFlow()

    /**
     * Whether the bound session actually carries an ImageAnalysis stream. False when the vendor
     * NIGHT extension can't host concurrent analysis, which silently stops QR scanning, luminance
     * sampling and Motion Photo; the viewfinder shows a notice so it isn't a mystery.
     */
    internal val analysisStreamActiveMutable = MutableStateFlow(false)
    val analysisStreamActive = analysisStreamActiveMutable.asStateFlow()

    /**
     * True while the live preview is bound with the CameraX NIGHT extension (plain
     * PHOTO mode, low light). When set, capture goes straight through the already
     * extension-enabled session instead of the momentary rebind path.
     */
    internal val nightPreviewActiveMutable = MutableStateFlow(false)
    val nightPreviewActive = nightPreviewActiveMutable.asStateFlow()

    // AE/AF lock (long-press-to-lock on the preview).
    internal val focusLockedMutable = MutableStateFlow(false)
    val focusLocked = focusLockedMutable.asStateFlow()

    // Video recording paused (between pause() and resume()).
    internal val recordingPausedMutable = MutableStateFlow(false)
    val recordingPaused = recordingPausedMutable.asStateFlow()

    // Mic mute for video (persisted). Applied live to the active recording.
    internal val micMutedMutable = MutableStateFlow(false)
    val micMuted = micMutedMutable.asStateFlow()

    // True when an ImageCapture is bound alongside the video session (in-recording snapshots).
    internal val videoSnapshotSupportedMutable = MutableStateFlow(false)
    val videoSnapshotSupported = videoSnapshotSupportedMutable.asStateFlow()

    // Hardware-shutter (volume key) events, collected by the UI to run the same capture action.
    internal val shutterEventsMutable = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shutterEvents = shutterEventsMutable.asSharedFlow()

    fun triggerShutter() {
        shutterEventsMutable.tryEmit(Unit)
    }

    // Burst mode (press-and-hold shutter): fires single-frame captures back-to-back with only one
    // in flight at a time, up to BURST_MAX, until the user releases (stopBurst).
    internal val burstActiveMutable = MutableStateFlow(false)
    val burstActive = burstActiveMutable.asStateFlow()

    internal val burstCountMutable = MutableStateFlow(0)
    val burstCount = burstCountMutable.asStateFlow()

    /**
     * One Motion-Photo ring-buffer entry: the last ~MOTION_WINDOW of analysis frames (RGB copies),
     * fed by the PhotoAnalyzer off the shared analysis stream and drained when a Motion Photo is
     * captured.
     */
    class MotionFrame(val bitmap: Bitmap, val timestampNanos: Long, val rotationDegrees: Int)
    internal val motionFrames = ArrayDeque<MotionFrame>()
    internal val motionLock = Any()

    // High-speed session state — Slo-Mo-only (back camera, true HFR, no fallbacks)
    internal var highSpeedVideoCapture: VideoCapture<Recorder>? = null
    internal var highSpeedRecording: Recording? = null

    internal val highSpeedActiveMutable = MutableStateFlow(false)
    val highSpeedActive = highSpeedActiveMutable.asStateFlow()

    /** Whether the back camera supports true HFR slo-mo on this device. */
    internal val sloMoSupportedMutable = MutableStateFlow<Boolean?>(null)
    val sloMoSupported = sloMoSupportedMutable.asStateFlow()

    // Manual video session state (VIDEO / TIMELAPSE modes)
    internal var videoCapture: VideoCapture<Recorder>? = null

    internal val videoSessionActiveMutable = MutableStateFlow(false)
    val videoSessionActive = videoSessionActiveMutable.asStateFlow()

    /** True when the active video session is encoding AV1 (vs. the default codec). */
    var recordingWithAv1: Boolean = false
        internal set

    /** True when the active video session is encoding HEVC/H.265. */
    var recordingWithHevc: Boolean = false
        internal set

    fun setSloMoFps(fps: Int) {
        sloMoFps = fps
    }

    // (cancelTimerCountdown lives in CameraViewModelActions.kt as an extension.)

    /**
     * Which camera route is in the foreground ("camera" vs "settings"), or null before first
     * composition. Set by CameraScreenEffects/SettingsPage; MainActivity gates the hardware
     * shutter on it so volume/space keys pressed in Settings don't queue a surprise capture
     * (and volume keys keep adjusting volume there).
     */
    @Volatile var foregroundRoute: String? = null

    init {
        loadSettings()
        panoramaEngine.onSweepComplete = { finishPanoramaSweep() }
        viewModelScope.launch {
            lastCaptureUriMutable.collect { uri -> _galleryThumbnail.value = loadThumbnail(uri) }
        }
        // Probe Slo-Mo capability once (back camera only). UI hides Slo-Mo if unsupported.
        viewModelScope.launch {
            sloMoSupportedMutable.value = probeSloMoSupport()
        }
        // DEBUG: Night mode resolution question – extension uses default resolution vs max-res,
        // may be lower so can't take full quality?
        startDebugLogging()
    }

    // Slo-Mo probe + thumbnail loading live in CameraStartup.kt as extensions.

    // Capture settings live in CameraSettings.kt as extensions.

    // --- Mode, lens, capture-intent, shutter, timer and sensor members stay here: ---
    // they are called from other packages (ui, root), which this task may not edit,
    // and Kotlin extensions would need new imports at those call sites.
    // Same-package-only helpers (crop ratio, level sensor, mirror flag, debug logging)
    // live in CameraViewModelActions.kt as extensions.

    // Bitmap/EXIF processing helpers live in CameraCaptureProcessing.kt as extensions.
    // (long-exposure countdown lives in CameraNightCapture.kt.)

    // Capture settings live in CameraSettings.kt as extensions.

    // Night luminance/override handling lives in CameraNightMode.kt as extensions.
    // Last-capture persistence lives in CameraSettings.kt as an extension.

    fun switchCameraMode(newMode: CameraMode) {
        // A pending shutter timer belongs to the old mode: firing capturePhoto() into the
        // new session would capture unprompted (and with the wrong use cases bound).
        if (newMode != cameraModeMutable.value) {
            cancelTimerCountdown()
            resetNightModeDetection()
        }
        // Slo-Mo is back-camera only; enforce it when entering Slo-Mo.
        if (newMode == CameraMode.SLOW_MO) {
            if (lensFacingMutable.value != CameraSelector.LENS_FACING_BACK) {
                lensFacingMutable.value = CameraSelector.LENS_FACING_BACK
                resetNightModeDetection()
            }
        }
        cameraModeMutable.value = newMode
    }

    fun flipCamera() {
        // Disallow flipping while in Slo-Mo — it's back-camera only, true HFR.
        if (cameraModeMutable.value == CameraMode.SLOW_MO) return
        lensFacingMutable.value = if (lensFacingMutable.value == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        // Preserve the lens family across the flip: e.g. UW back -> front lens,
        // tele back -> front lens (front has a single family, anchored at its default).
        val facing = if (lensFacingMutable.value == CameraSelector.LENS_FACING_BACK)
            com.vayunmathur.camera.domain.LensFacing.BACK
        else com.vayunmathur.camera.domain.LensFacing.FRONT
        selectedLensMutable.value =
            com.vayunmathur.camera.domain.LensSelectionLogic.filterByFacing(
                availableLensesMutable.value, facing
            ).minByOrNull { it.fallbackPriority }
        resetNightModeDetection()
    }

    /**
     * Selects a physical lens within the current facing family. The session
     * rebind effect is keyed on [selectedLens], so this triggers a rebind with
     * the filtered selector plus a capability refresh. Unknown lenses are
     * ignored so a stale UI entry can never crash a bind.
     */
    fun selectLens(lens: com.vayunmathur.camera.domain.PhysicalLens) {
        if (lens.facing != (
            if (lensFacingMutable.value == CameraSelector.LENS_FACING_BACK)
                com.vayunmathur.camera.domain.LensFacing.BACK
            else com.vayunmathur.camera.domain.LensFacing.FRONT
            )
        ) {
            return
        }
        if (lens !in availableLensesMutable.value) return
        selectedLensMutable.value = lens
        resetNightModeDetection()
    }

    // (night reset helper lives in CameraNightMode.kt.)
    // (mirrorCaptures lives in CameraViewModelActions.kt as an extension.)

    // Zoom/focus/analyzer controls live in CameraControls.kt as extensions.

    // Location refresh lives in CameraSettings.kt as an extension.

    // Slo-Mo session setup lives in CameraSloMoSession.kt as an extension.

    // bindSession lives in CameraSessions.kt as an extension.

    // Photo/night/pano/portrait session setups live in CameraPhotoSessions.kt as extensions.

    // Manual-control range reading lives in CameraManualControls.kt as an extension.

    // Video session setup lives in CameraVideoSession.kt as an extension.

    // Video capability helpers live in CameraSessions.kt as extensions.

    // Video tuning helpers (fps range, stabilization, capture options) also live there.

    // Session teardown lives in CameraSessions.kt as an extension.

    // --- Unified manual session control wiring (targets the single bound camera) ---
    // Implementations live in CameraControls.kt as extensions.
    // Flash-mode mapping lives in CameraSessions.kt as an extension.

    // Recording timers live in CameraControls.kt as extensions.

    // Slo-mo + standard recording live in CameraVideoRecording.kt as extensions.

    // --- Standard capture routing, burst and Motion Photo live in CameraPhotoCapture.kt. ---
    // Single-shot + IMAGE_CAPTURE paths live in CameraSingleCapture.kt.

    // Night-shutter routing lives in CameraPhotoCapture.kt; capture paths live in CameraNightCapture.kt.
    // IMAGE_CAPTURE paths live in CameraSingleCapture.kt.

    // (bitmap/EXIF helpers live in CameraCaptureProcessing.kt.)

    // (long-exposure countdown lives in CameraNightCapture.kt.)

    // Video recording + panorama live in CameraVideoRecording.kt as extensions.

    override fun onCleared() {
        unregisterLevelSensor()
        try { bokehExecutor.shutdown() } catch (_: Exception) {}
        try { analysisExecutor.shutdown() } catch (_: Exception) {}
        try { stillBokeh.close() } catch (_: Exception) {}
    }
}
// (ManualLifecycleOwner lives in CameraViewModelTypes.kt.)
