package com.vayunmathur.camera.platform

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
import com.vayunmathur.camera.domain.MotionSaveQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.math.atan2
import com.vayunmathur.camera.data.SaveTarget
import com.vayunmathur.camera.data.loadSettings

/**
 * State and session owner for the single camera screen: the Camera2 sessions, every
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

        // Processed stills (AVIF encodes above all) still saving after
        // their shutter was released. Each holds a full-res source until
        // encoded, so keep this small: one encoding plus one waiting. A
        // press beyond it keeps the synchronous save rather than dropping
        // or rerouting the shot.
        const val MAX_PENDING_STILL_SAVES = 2

        val EXPOSURE_TIME_STOPS: List<ExposureTimeStop> = EXPOSURE_TIME_STOPS_TABLE

        /**
         * EXIF tags copied from the original frame onto an adjusted capture. Orientation and GPS
         * are set separately (see writeCaptureExif); dimension tags are omitted so they aren't
         * left inconsistent with the re-encoded JPEG.
         */
        val EXIF_TAGS_TO_COPY: List<String> = EXIF_TAGS_TO_COPY_TABLE
    }

    internal val ds = DataStoreUtils.getInstance(app)

    /**
     * Launch-to-first-frame ordering breadcrumb: the Camera2 bind must be
     * requested before the background probes (lens enumeration, extension /
     * slo-mo capability probes) complete. Read by the `CameraStartup` log
     * lines; guarded by [StartupOrderTrackerTest].
     */
    internal val startupOrder =
        com.vayunmathur.camera.platform.camera2.StartupOrderTracker()

    // Startup bind-request breadcrumb lives in CameraViewModelStartup.kt as an extension.

    // Camera2 gate setters/fallback live in CameraViewModelCamera2Gates.kt as extensions.

    /** One-shot latch for a failed Camera2 bind attempt (surfaces the retryable bind error). */
    internal val camera2FallbackLatch =
        com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch()
    internal val camera2FallbackBindKeyMutable = MutableStateFlow<String?>(null)
    val camera2FallbackBindKey = camera2FallbackBindKeyMutable.asStateFlow()


    /** One-shot latch for a failed Camera2 slo-mo attempt (surfaces the retryable bind error). */
    internal val camera2SloMoFallbackLatch =
        com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch()
    internal val camera2SloMoFallbackBindKeyMutable = MutableStateFlow<String?>(null)
    val camera2SloMoFallbackBindKey = camera2SloMoFallbackBindKeyMutable.asStateFlow()

    /** Live Camera2 high-speed session while the gated slo-mo preview is composed; null otherwise. */
    internal var activeCamera2HighSpeedSession:
        com.vayunmathur.camera.platform.camera2.Camera2HighSpeedSession? = null


    /**
     * The live Camera2 NIGHT extension session while one is bound
     * (Phase 6), registered by its owner; null otherwise. The night glue
     * reads it synchronously — it is only ever set/cleared on the main
     * thread from composition effects, like [activeCamera2Session].
     */
    internal var activeCamera2ExtensionSession:
        com.vayunmathur.camera.platform.camera2.Camera2ExtensionSession? = null

    /** True while a Camera2 extension session is registered (strength UI hides). */
    internal val camera2NightSessionActiveMutable = MutableStateFlow(false)
    val camera2NightSessionActive = camera2NightSessionActiveMutable.asStateFlow()

    /**
     * Bind key latched after a Camera2 NIGHT extension preview failed to
     * configure/open for this lens+mode; the UI then falls back to the
     * regular preview (Camera2 or CameraX) for that bind instead of
     * retry-looping the extension session. Session-scoped (not the 7-day
     * persisted failure cache): a probe miss on one lens must not suppress
     * the CameraX extension — capture-time failures still record into
     * that cache via `recordNightExtensionFailure`.
     */
    internal val camera2NightFallbackBindKeyMutable = MutableStateFlow<String?>(null)
    val camera2NightFallbackBindKey = camera2NightFallbackBindKeyMutable.asStateFlow()

    /**
     * The live Camera2 session while the gated preview is composed
     * (Phase 4a stills), registered by `Camera2Preview`; null otherwise.
     * The shutter glue reads it synchronously — it is only ever set/cleared
     * on the main thread from composition effects.
     */
    internal var activeCamera2Session:
        com.vayunmathur.camera.platform.camera2.Camera2Session? = null

    internal val cameraModeMutable = MutableStateFlow(CameraMode.PHOTO)
    val cameraMode = cameraModeMutable.asStateFlow()

    internal val lensFacingMutable = MutableStateFlow(android.hardware.camera2.CameraMetadata.LENS_FACING_BACK)
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

    internal val photoFormatMutable = MutableStateFlow(PhotoFormat.JPEG)
    val photoFormat = photoFormatMutable.asStateFlow()

    // Motion-Photo clip length (issue #712). Default 3s ≈ the old ~1.5s-12-frame buffer.
    internal val motionPhotoDurationMutable = MutableStateFlow(MotionPhotoDuration.THREE)
    val motionPhotoDuration = motionPhotoDurationMutable.asStateFlow()

    // Motion Photo on/off. Default on preserves the pre-toggle behaviour:
    // eligible plain stills diverted to Motion Photo with no explicit opt-in.
    internal val motionPhotoEnabledMutable = MutableStateFlow(true)
    val motionPhotoEnabled = motionPhotoEnabledMutable.asStateFlow()

    internal val audioInputDeviceMutable =
        MutableStateFlow(com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection.AUTO)
    val audioInputDevice = audioInputDeviceMutable.asStateFlow()

    // No recording audio-codec state: audio is always Opus
    // (AudioEncoderPolicy), with a video-only recording when a device
    // has no Opus encoder. There is nothing for the user to select.

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

    // Background processed-still saves: the encode + MediaStore landing
    // runs after the shutter is released, one at a time (the mutex), in
    // this scope so a shot taken just before leaving still lands. The
    // dispatcher is a var only so tests can drive the state machine.
    internal val stillSaveQueue = com.vayunmathur.camera.domain.StillSaveQueue(MAX_PENDING_STILL_SAVES)
    internal val stillSaveMutex = Mutex()
    internal val stillSaveScope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.error("CameraViewModel", "Background still save failed", e)
        }
    )
    internal var stillSaveDispatcher: CoroutineDispatcher = Dispatchers.IO

    internal val locationEnabledMutable = MutableStateFlow(false)
    val locationEnabled = locationEnabledMutable.asStateFlow()

    internal val lastCaptureUriMutable = MutableStateFlow<Uri?>(null)
    val lastCaptureUri = lastCaptureUriMutable.asStateFlow()

    internal val _galleryThumbnail = MutableStateFlow<Bitmap?>(null)
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

    // Last CONTROL_AE_STATE reported by the preview stream (CameraMetadata.CONTROL_AE_STATE_*),
    // or null before the first result. The ZSL capture gate (CameraPhotoCapture.kt) reads it:
    // CameraX's zero-shutter-lag ring buffer only holds converged frames, so while AE is still
    // searching after a lighting change the newest buffered frame is exposed for the previous
    // scene and a press would save that stale exposure.
    @Volatile internal var lastAeState: Int? = null

    internal val aeSnapshotCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: android.hardware.camera2.CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeIso = it }
            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeExposureNanos = it }
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)?.let { lastAeState = it }
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

    // Whether live state lets the photo session use zero-shutter-lag capture: exposure and ISO on
    // Auto, night off (see StillCaptureModePolicy). The photo session rebinds when this flips,
    // but never mid-shot: while a capture is in flight the previous value is held (#914).
    val zslCompatible = combine(
        exposureTimeIndexMutable, manualIsoIndexMutable, nightModeActive, isCapturingMutable
    ) { exposure, iso, night, capturing -> ZslGate(exposure == 0 && iso == 0 && !night, capturing) }
        .runningFold(true) { held, gate -> if (gate.capturing) held else gate.compatible }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The user's chosen bind-time settings, before any recording deferral. */
    private val desiredBindSettings = combine(
        videoCodecMutable, audioInputDeviceMutable, mirrorFrontMutable
    ) { video, device, mirror -> BindTimeSettings(video, device, mirror) }

    /**
     * The bind-time settings a session bind must actually use. Codec, audio input device and
     * mirroring are fixed when the Recorder/VideoCapture is built, so changing them used
     * to rebind the session mid-recording: teardown stopped the active recording with no
     * warning and the user lost the take believing it continued. While recording, this
     * snapshot is held at the bound values (the desired values still persist and show in
     * Settings); when the recording stops it releases to the desired values, whose change
     * is the session-effect key that rebinds for the next recording. Same held-value
     * pattern as [zslCompatible] while a still is in flight.
     */
    val bindTimeSettings = combine(desiredBindSettings, isRecordingMutable) { desired, recording ->
        BindSettingsGate(desired, recording)
    }
        .runningFold(
            BindTimeSettings(
                CodecSupport.defaultVideoCodec,
                com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection.AUTO, true
            )
        ) { held, gate -> if (gate.recording) held else gate.settings }
        .stateIn(
            viewModelScope, SharingStarted.Eagerly,
            BindTimeSettings(
                CodecSupport.defaultVideoCodec,
                com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection.AUTO, true
            )
        )

    /** True while a bind-time setting has been changed mid-recording and awaits the stop. */
    val deferredBindSettingsPending = combine(
        desiredBindSettings, bindTimeSettings, isRecordingMutable
    ) { desired, applied, recording -> recording && desired != applied }
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

    // Live NIGHT-extension processing strength (0..100) when a session
    // reports one; null when unavailable. The Camera2 extension session
    // has no strength flow, so this stays null on that path (hidden, not faked).
    internal val extensionStrengthMutable = MutableStateFlow<Int?>(null)
    val extensionStrength = extensionStrengthMutable.asStateFlow()

    internal val longExposureProgressMutable = MutableStateFlow(0f)
    val longExposureProgress = longExposureProgressMutable.asStateFlow()

    internal val longExposureRemainingMutable = MutableStateFlow("")
    val longExposureRemaining = longExposureRemainingMutable.asStateFlow()

    internal var longExposureTimerJob: kotlinx.coroutines.Job? = null

    internal var lastLocation: Location? = null
    internal var sloMoFps: Int = 30

    /** Camera2 recording state (video + slo-mo engines/cores); see Camera2RecordingState. */
    internal val camera2RecordingState = Camera2RecordingState()
    internal val camera2RecordingMutable get() = camera2RecordingState.recordingActive
    val camera2Recording = camera2RecordingState.recordingActive.asStateFlow()

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

    // Session bind exhausted its bounded retries: the preview has no surface,
    // so the UI shows an error with a manual retry instead of a black frame.
    // Cleared when a bind succeeds, a bind (re)starts, or a key change
    // supersedes the failed bind.
    internal val sessionBindFailedMutable = MutableStateFlow(false)
    val sessionBindFailed = sessionBindFailedMutable.asStateFlow()

    // Monotonic manual-retry counter: bumping it is a session-effect key, so
    // a tap re-runs the bind (cancelling the parked failed effect first).
    internal val sessionBindRetryMutable = MutableStateFlow(0)
    val sessionBindRetry = sessionBindRetryMutable.asStateFlow()

    // Monotonic count of teardownSession() calls. The UI session effect owns binding; a
    // capture-time rebind (captureNightPhotoExtension) snapshots this so its finally-restore
    // can tell whether the effect already tore down / rebound meanwhile (mode switch, lens
    // flip, backgrounding) — restoring then would unbind the effect's fresh session and
    // strand the UI on a photo session in e.g. VIDEO mode (frozen/black preview).
    internal var sessionTeardownCount: Int = 0

    /** Per-mode Camera2 analysis sink; see Camera2AnalysisGlue. */
    @Volatile internal var analysisSink: ((com.vayunmathur.camera.platform.camera2.AnalysisFrame) -> Unit)? = null

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

    internal val highSpeedActiveMutable = MutableStateFlow(false)
    val highSpeedActive = highSpeedActiveMutable.asStateFlow()

    /** Whether the back camera supports true HFR slo-mo on this device. */
    internal val sloMoSupportedMutable = MutableStateFlow<Boolean?>(null)
    val sloMoSupported = sloMoSupportedMutable.asStateFlow()

    internal val videoSessionActiveMutable = MutableStateFlow(false)
    val videoSessionActive = videoSessionActiveMutable.asStateFlow()

    /** True when the active video session is encoding AV1 (vs. the default codec). */
    var recordingWithAv1: Boolean = false
        internal set

    /** True when the active video session is encoding HEVC/H.265. */
    var recordingWithHevc: Boolean = false
        internal set

    // --- Adaptive video bitrate (pre-recording only; see AdaptiveVideoBitratePolicy) ---

    /** Smoothed scene-motion estimate fed by the video session's VideoMotionAnalyzer. */
    internal val sceneMotionEstimator =
        com.vayunmathur.camera.domain.SceneMotionEstimator()

    /** Latest scene classification while previewing in a video mode; null until sampled. */
    internal val sceneMotionMutable =
        MutableStateFlow<com.vayunmathur.camera.domain.SceneMotion?>(null)
    val sceneMotion = sceneMotionMutable.asStateFlow()

    /** Last adaptive decision made (bind time or record start); kept for tests/debug. */
    internal var lastAdaptiveBitrateDecision:
        com.vayunmathur.camera.domain.AdaptiveBitrateDecision? = null

    /** Bitrate the bound Recorder was actually built with; null outside a video session. */
    internal var boundVideoBitrateBps: Int? = null

    /** Ladder tier the bound video session resolved to. */
    internal var boundVideoQualityTier: com.vayunmathur.camera.domain.VideoQualityTier? = null

    /** True while a record press is rebinding the video session for a new bitrate. */
    internal var adaptiveBitrateRebindInFlight: Boolean = false


    // (cancelTimerCountdown lives in CameraViewModelActions.kt as an extension.)

    /**
     * Which camera route is in the foreground ("camera" vs "settings"), or null before first
     * composition. Set by CameraScreenEffects/SettingsPage; MainActivity gates the hardware
     * shutter on it so volume/space keys pressed in Settings don't queue a surprise capture
     * (and volume keys keep adjusting volume there).
     */
    @Volatile var foregroundRoute: String? = null

    // Startup sequencing lives in CameraViewModelStartup.kt as an extension.
    init {
        runStartupSequence()
    }

    // Slo-Mo probe + thumbnail loading live in CameraStartup.kt as extensions.

    // Capture settings live in CameraSettings.kt as extensions.

    // --- Mode/lens actions live in CameraViewModelModeLens.kt as extensions;
    // shutter/timer/sensor helpers live in CameraViewModelActions.kt as extensions.
    // Capture-intent, timer and sensor members stay here.

    // Bitmap/EXIF processing helpers live in CameraCaptureProcessing.kt as extensions.
    // (long-exposure countdown lives in CameraNightCapture.kt.)

    // Capture settings live in CameraSettings.kt as extensions.

    // Night luminance/override handling lives in CameraNightMode.kt as extensions.
    // Last-capture persistence lives in CameraSettings.kt as an extension.


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

