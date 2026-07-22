package com.vayunmathur.camera.util

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.camera.lifecycle.awaitInstance
import java.io.ByteArrayInputStream
import kotlin.math.atan2
import kotlin.math.roundToInt

enum class CameraMode { PHOTO, PORTRAIT, PANORAMA, PHOTOSPHERE, VIDEO, SLOW_MO, TIMELAPSE, CINEMATIC }
enum class FlashMode { ON, OFF, AUTO }
enum class TimerDuration(val seconds: Int) { NONE(0), THREE(3), FIVE(5), TEN(10) }
enum class AspectRatioOption(val label: String) { RATIO_16_9("16:9"), RATIO_4_3("4:3"), RATIO_1_1("1:1") }
enum class VideoCodec(val label: String, val description: String) {
    AVC("H.264 / AVC", "Most compatible"),
    HEVC("H.265 / HEVC", "More efficient"),
    AV1("AV1", "Most efficient"),
}

/** Formats a zoom ratio for the zoom bar: ".5", "1x", "2x", or "1.5x". */
fun formatZoomLabel(ratio: Float): String = when {
    ratio < 1f -> ".${(ratio * 10).roundToInt()}"
    else -> {
        val rounded = (ratio * 10f).roundToInt() / 10f
        if (kotlin.math.abs(rounded - rounded.roundToInt()) < 0.05f) "${rounded.roundToInt()}x"
        else "%.1fx".format(rounded)
    }
}

data class ExposureTimeStop(val label: String, val nanos: Long?)

/**
 * Builds the warmth/shadows color matrix shared by the live preview and the
 * saved capture, so a photo looks the same as what the viewfinder showed.
 */
fun buildColorAdjustmentMatrix(warmth: Float, shadows: Float): ColorMatrix = ColorMatrix(
    floatArrayOf(
        1f + warmth * 0.15f, 0f, 0f, 0f, shadows * 40f,
        0f, 1f + warmth * 0.05f, 0f, 0f, shadows * 40f,
        0f, 0f, 1f - warmth * 0.15f, 0f, shadows * 40f,
        0f, 0f, 0f, 1f, 0f,
    )
)

class CameraViewModel(private val app: Application) : AndroidViewModel(app) {
    companion object {

        // Night-mode auto-detection tuning. Engage once average Y stays below ENGAGE for a few
        // frames; disengage once it climbs above DISENGAGE for a few frames. The gap between the
        // two thresholds is the hysteresis band that stops the moon button from flickering.
        private const val NIGHT_ENGAGE_LUMA = 40f
        private const val NIGHT_DISENGAGE_LUMA = 55f
        private const val NIGHT_DEBOUNCE_FRAMES = 4

        // Target night exposure/ISO used when night mode fires on an Auto exposure stop. The
        // single-frame emulation (fallback) uses the long ~1/4s target; the multi-frame burst uses
        // a shorter per-frame exposure so each frame has less motion blur and the merge recovers SNR.
        private const val NIGHT_TARGET_EXPOSURE_NANOS = 250_000_000L // ~1/4s
        private const val NIGHT_BURST_PER_FRAME_NANOS = 100_000_000L // ~1/10s, in the 1/15–1/8s range
        private const val NIGHT_ISO_FRACTION = 0.75f

        /** Safety cap on frames captured during a single press-and-hold burst. */
        private const val BURST_MAX = 30

        // Motion-Photo ring buffer: keep ~1.5s of analysis frames, capped by count to bound memory
        // (analysis frames can be high-res, so this count is deliberately conservative).
        private const val MOTION_WINDOW_NANOS = 1_500_000_000L
        private const val MOTION_MAX_FRAMES = 12

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
        private val EXIF_TAGS_TO_COPY = listOf(
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

    private val ds = DataStoreUtils.getInstance(app)

    private val _cameraMode = MutableStateFlow(CameraMode.PHOTO)
    val cameraMode = _cameraMode.asStateFlow()

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing = _lensFacing.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode = _flashMode.asStateFlow()

    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled = _torchEnabled.asStateFlow()

    private val _timerDuration = MutableStateFlow(TimerDuration.NONE)
    val timerDuration = _timerDuration.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatioOption.RATIO_4_3)
    val aspectRatio = _aspectRatio.asStateFlow()

    private val _videoCodec = MutableStateFlow(VideoCodec.AVC)
    val videoCodec = _videoCodec.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSec = _recordingDurationSec.asStateFlow()
    private var recordingTimerJob: kotlinx.coroutines.Job? = null

    private val _timerCountdown = MutableStateFlow(0)
    val timerCountdown = _timerCountdown.asStateFlow()
    private var timerCountdownJob: kotlinx.coroutines.Job? = null

    private val _qrResult = MutableStateFlow<String?>(null)
    val qrResult = _qrResult.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio = _zoomRatio.asStateFlow()

    private val _availableZoomLevels = MutableStateFlow(listOf("1x" to 1f))
    val availableZoomLevels = _availableZoomLevels.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing = _isCapturing.asStateFlow()

    private val _locationEnabled = MutableStateFlow(false)
    val locationEnabled = _locationEnabled.asStateFlow()

    private val _lastCaptureUri = MutableStateFlow<Uri?>(null)
    val lastCaptureUri = _lastCaptureUri.asStateFlow()

    private val _galleryThumbnail = MutableStateFlow<Bitmap?>(null)
    val galleryThumbnail = _galleryThumbnail.asStateFlow()

    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled = _gridEnabled.asStateFlow()

    // Horizon level indicator: a device-roll angle (degrees) read off the accelerometer/gravity
    // sensor, exposed only while the level overlay is enabled. Registered lazily so the sensor
    // isn't running when the overlay is off.
    private val _levelEnabled = MutableStateFlow(false)
    val levelEnabled = _levelEnabled.asStateFlow()

    private val _roll = MutableStateFlow(0f)
    val roll = _roll.asStateFlow()

    private val sensorManager by lazy { app.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            // 0° when the device is held upright in portrait; positive as the top edge tilts right.
            _roll.value = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private var levelSensorRegistered = false

    // Portrait blur strength (0..1 UI → ~0.4..1.8 shader blurScale). Scales the bokeh shader's
    // tap offsets so the user can dial the background blur up/down. Default 0.5 ≈ 1.0 blurScale.
    private val _blurStrength = MutableStateFlow(0.5f)
    val blurStrength = _blurStrength.asStateFlow()

    private val _exposureCompensation = MutableStateFlow(0f)
    val exposureCompensation = _exposureCompensation.asStateFlow()

    private val _warmth = MutableStateFlow(0f)
    val warmth = _warmth.asStateFlow()

    private val _shadows = MutableStateFlow(0f)
    val shadows = _shadows.asStateFlow()

    private val _exposureTimeIndex = MutableStateFlow(0)
    val exposureTimeIndex = _exposureTimeIndex.asStateFlow()

    // --- Manual pro controls (ISO). Index 0 == Auto. ---

    // ISO: index 0 == Auto; otherwise an index into [_isoStops] (+1). Stops are derived from the
    // sensor's SENSOR_INFO_SENSITIVITY_RANGE when the session binds.
    private val _manualIsoIndex = MutableStateFlow(0)
    val manualIsoIndex = _manualIsoIndex.asStateFlow()

    private val _isoStops = MutableStateFlow<List<Int>>(emptyList())
    val isoStops = _isoStops.asStateFlow()

    // Last auto-converged AE ISO / exposure, snapshotted off the preview's capture results so a
    // half-manual exposure (only ISO or only shutter set) can seed the other from the auto value.
    @Volatile private var lastAeIso: Int? = null
    @Volatile private var lastAeExposureNanos: Long? = null

    private val aeSnapshotCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
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
    private val _lowLightDetected = MutableStateFlow(false)
    val lowLightDetected = _lowLightDetected.asStateFlow()

    private val _nightModeOverriddenOff = MutableStateFlow(false)
    val nightModeOverriddenOff = _nightModeOverriddenOff.asStateFlow()

    val nightModeActive = combine(_lowLightDetected, _nightModeOverriddenOff) { low, off -> low && !off }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Consecutive-frame counters backing the hysteresis + debounce in onLuminance().
    private var lowLumaFrames = 0
    private var highLumaFrames = 0

    private val _longExposureProgress = MutableStateFlow(0f)
    val longExposureProgress = _longExposureProgress.asStateFlow()

    private val _longExposureRemaining = MutableStateFlow("")
    val longExposureRemaining = _longExposureRemaining.asStateFlow()

    private var longExposureTimerJob: kotlinx.coroutines.Job? = null

    private var lastLocation: Location? = null
    private var currentRecording: Recording? = null
    private var sloMoFps: Int = 30

    // IMAGE_CAPTURE (system "take a photo") intent state. When capturing for a caller, we either
    // write the full-res JPEG to their EXTRA_OUTPUT Uri, or hand back a downscaled thumbnail.
    var captureForResult: Boolean = false
        private set
    private var resultOutputUri: Uri? = null

    /** Enters capture-for-result mode; [outputUri] is the caller's EXTRA_OUTPUT (may be null). */
    fun enableCaptureForResult(outputUri: Uri?) {
        captureForResult = true
        resultOutputUri = outputUri
    }

    val panoramaEngine = PanoramaEngine(app)

    // Unified manual session state (all modes bind through one CameraXViewfinder).
    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest = _surfaceRequest.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var sessionLifecycleOwner: ManualLifecycleOwner? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    // The analyzer currently attached to imageAnalysis, so the night burst can swap in a temporary
    // frame collector and restore the previous analyzer (PhotoAnalyzer) when it finishes.
    private var currentAnalyzer: ImageAnalysis.Analyzer? = null

    // Latest device orientation as a Surface.ROTATION_* constant. Driven by the UI's
    // OrientationEventListener and applied to ImageCapture so landscape shots save
    // with the correct orientation even while the activity stays locked to portrait.
    private var targetRotation: Int = Surface.ROTATION_0

    /** True once the photo session's use cases (incl. ImageAnalysis) are bound. */
    private val _photoSessionActive = MutableStateFlow(false)
    val photoSessionActive = _photoSessionActive.asStateFlow()

    // AE/AF lock (long-press-to-lock on the preview).
    private val _focusLocked = MutableStateFlow(false)
    val focusLocked = _focusLocked.asStateFlow()

    // Video recording paused (between pause() and resume()).
    private val _recordingPaused = MutableStateFlow(false)
    val recordingPaused = _recordingPaused.asStateFlow()

    // Mic mute for video (persisted). Applied live to the active recording.
    private val _micMuted = MutableStateFlow(false)
    val micMuted = _micMuted.asStateFlow()

    // True when an ImageCapture is bound alongside the video session (in-recording snapshots).
    private val _videoSnapshotSupported = MutableStateFlow(false)
    val videoSnapshotSupported = _videoSnapshotSupported.asStateFlow()

    // Hardware-shutter (volume key) events, collected by the UI to run the same capture action.
    private val _shutterEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shutterEvents = _shutterEvents.asSharedFlow()

    fun triggerShutter() {
        _shutterEvents.tryEmit(Unit)
    }

    // Burst mode (press-and-hold shutter): fires single-frame captures back-to-back with only one
    // in flight at a time, up to BURST_MAX, until the user releases (stopBurst).
    private val _burstActive = MutableStateFlow(false)
    val burstActive = _burstActive.asStateFlow()

    private val _burstCount = MutableStateFlow(0)
    val burstCount = _burstCount.asStateFlow()

    // Motion-Photo ring buffer: the last ~MOTION_WINDOW of analysis frames (RGB copies), fed by the
    // PhotoAnalyzer off the shared analysis stream and drained when a Motion Photo is captured.
    private class MotionFrame(val bitmap: Bitmap, val timestampNanos: Long, val rotationDegrees: Int)
    private val motionFrames = ArrayDeque<MotionFrame>()
    private val motionLock = Any()

    // High-speed session state
    private var highSpeedVideoCapture: VideoCapture<Recorder>? = null
    private var highSpeedRecording: Recording? = null

    private val _highSpeedActive = MutableStateFlow(false)
    val highSpeedActive = _highSpeedActive.asStateFlow()

    // Manual video session state (VIDEO / TIMELAPSE modes)
    private var videoCapture: VideoCapture<Recorder>? = null

    private val _videoSessionActive = MutableStateFlow(false)
    val videoSessionActive = _videoSessionActive.asStateFlow()

    /** True when the active video session is encoding AV1 (vs. the default codec). */
    var recordingWithAv1: Boolean = false
        private set

    /** True when the active video session is encoding HEVC/H.265. */
    var recordingWithHevc: Boolean = false
        private set

    fun setSloMoFps(fps: Int) {
        sloMoFps = fps
    }

    init {
        loadSettings()
        panoramaEngine.onSweepComplete = { finishPanoramaSweep() }
        viewModelScope.launch {
            _lastCaptureUri.collect { uri -> _galleryThumbnail.value = loadThumbnail(uri) }
        }
    }

    private suspend fun loadThumbnail(uri: Uri?): Bitmap? = uri?.let {
        withContext(Dispatchers.IO) {
            try {
                app.contentResolver.loadThumbnail(it, Size(96, 96), null)
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to load gallery thumbnail", e)
                null
            }
        }
    }

    private fun loadSettings() {
        ds.getString("camera_flash")?.let { _flashMode.value = FlashMode.valueOf(it) }
        ds.getString("camera_timer")?.let { _timerDuration.value = TimerDuration.valueOf(it) }
        ds.getString("camera_aspect_ratio")?.let { _aspectRatio.value = AspectRatioOption.valueOf(it) }
        ds.getString("camera_video_codec")?.let {
            _videoCodec.value = try { VideoCodec.valueOf(it) } catch (_: Exception) { VideoCodec.AVC }
        }
        ds.getString("camera_location")?.let { _locationEnabled.value = it.toBoolean() }
        ds.getString("camera_last_capture")?.let { _lastCaptureUri.value = Uri.parse(it) }
        ds.getString("camera_grid")?.let { _gridEnabled.value = it.toBoolean() }
        ds.getString("camera_level")?.let { _levelEnabled.value = it.toBoolean() }
        ds.getString("camera_mic_muted")?.let { _micMuted.value = it.toBoolean() }
        if (_levelEnabled.value) registerLevelSensor()
    }

    fun setFlashMode(mode: FlashMode) {
        _flashMode.value = mode
        viewModelScope.launch { ds.setString("camera_flash", mode.name) }
    }

    fun toggleTorch() {
        _torchEnabled.value = !_torchEnabled.value
    }

    fun setTimerDuration(duration: TimerDuration) {
        _timerDuration.value = duration
        viewModelScope.launch { ds.setString("camera_timer", duration.name) }
    }

    fun setAspectRatio(ratio: AspectRatioOption) {
        _aspectRatio.value = ratio
        viewModelScope.launch { ds.setString("camera_aspect_ratio", ratio.name) }
    }

    /** Cycles the aspect ratio 4:3 → 16:9 → 1:1 → 4:3 (top-bar icon). */
    fun cycleAspectRatio() {
        val order = listOf(
            AspectRatioOption.RATIO_4_3,
            AspectRatioOption.RATIO_16_9,
            AspectRatioOption.RATIO_1_1
        )
        val next = order[(order.indexOf(_aspectRatio.value) + 1) % order.size]
        setAspectRatio(next)
    }

    fun setLocationEnabled(enabled: Boolean) {
        _locationEnabled.value = enabled
        viewModelScope.launch { ds.setString("camera_location", enabled.toString()) }
    }

    fun setVideoCodec(codec: VideoCodec) {
        _videoCodec.value = codec
        viewModelScope.launch { ds.setString("camera_video_codec", codec.name) }
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
        viewModelScope.launch { ds.setString("camera_grid", _gridEnabled.value.toString()) }
    }

    fun toggleLevel() {
        _levelEnabled.value = !_levelEnabled.value
        if (_levelEnabled.value) registerLevelSensor() else unregisterLevelSensor()
        viewModelScope.launch { ds.setString("camera_level", _levelEnabled.value.toString()) }
    }

    private fun registerLevelSensor() {
        if (levelSensorRegistered) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(levelListener, sensor, SensorManager.SENSOR_DELAY_UI)
        levelSensorRegistered = true
    }

    private fun unregisterLevelSensor() {
        if (!levelSensorRegistered) return
        sensorManager.unregisterListener(levelListener)
        levelSensorRegistered = false
    }

    /** Maps the 0..1 blur-strength UI value to the bokeh shader's blurScale multiplier. */
    fun setBlurStrength(value: Float) {
        _blurStrength.value = value.coerceIn(0f, 1f)
    }

    fun setExposureCompensation(value: Float) {
        _exposureCompensation.value = value
    }

    fun setWarmth(value: Float) {
        _warmth.value = value
    }

    fun setShadows(value: Float) {
        _shadows.value = value
    }

    fun setExposureTimeIndex(index: Int) {
        _exposureTimeIndex.value = index.coerceIn(0, EXPOSURE_TIME_STOPS.lastIndex)
        applyManualControls()
    }

    /** ISO index 0 == Auto; otherwise a 1-based index into [_isoStops]. */
    fun setManualIsoIndex(index: Int) {
        _manualIsoIndex.value = index.coerceIn(0, _isoStops.value.size)
        applyManualControls()
    }

    private fun camera2ControlOrNull(): androidx.camera.camera2.interop.Camera2CameraControl? = try {
        boundCamera?.cameraControl?.let {
            androidx.camera.camera2.interop.Camera2CameraControl.from(it)
        }
    } catch (e: Exception) {
        Log.w("CameraViewModel", "Camera2 control unavailable", e)
        null
    }

    /** The manual ISO for the current index, or null when set to Auto. */
    private fun manualIso(): Int? =
        _manualIsoIndex.value.takeIf { it > 0 }?.let { _isoStops.value.getOrNull(it - 1) }

    /** True when both shutter and ISO are on Auto (no manual exposure). */
    private fun isExposureAuto(): Boolean =
        _exposureTimeIndex.value == 0 && _manualIsoIndex.value == 0

    /**
     * Rebuilds a single [CaptureRequestOptions] from the current manual exposure/ISO state and
     * pushes it to the bound camera, affecting the live preview and subsequent stills. When on Auto
     * the options are cleared, reverting to CameraX's default auto behavior (including tap-to-focus).
     * Called on every manual-control change and re-applied after a session rebind.
     */
    fun applyManualControls() {
        val cam2 = camera2ControlOrNull() ?: return
        val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()

        // Manual exposure / ISO with linkage: if either is manual, lock AE off and set both,
        // seeding the un-set one from the last auto-converged value (or a sensible default).
        val manualShutter = EXPOSURE_TIME_STOPS[_exposureTimeIndex.value].nanos
        val manualIso = manualIso()
        if (manualShutter != null || manualIso != null) {
            val exposure = manualShutter ?: lastAeExposureNanos ?: 16_666_667L // ~1/60s
            val iso = manualIso ?: lastAeIso ?: _isoStops.value.getOrNull(_isoStops.value.size / 2) ?: 400
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
            )
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, exposure
            )
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, iso
            )
        }

        try {
            // An empty options set clears any previously-applied manual 3A → full auto.
            cam2.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to apply manual controls", e)
        }
    }

    private fun resetManualControls() {
        _manualIsoIndex.value = 0
        _exposureTimeIndex.value = 0
    }

    /**
     * Feeds the PhotoAnalyzer's average scene luminance through a hysteresis + debounce filter so
     * night mode engages/disengages smoothly. When the scene brightens back up (true→false), the
     * user's per-scene override is reset so the next dark scene re-engages cleanly.
     */
    fun onLuminance(avg: Float) {
        if (_lowLightDetected.value) {
            if (avg > NIGHT_DISENGAGE_LUMA) {
                highLumaFrames++
                lowLumaFrames = 0
                if (highLumaFrames >= NIGHT_DEBOUNCE_FRAMES) {
                    _lowLightDetected.value = false
                    _nightModeOverriddenOff.value = false
                    highLumaFrames = 0
                }
            } else {
                highLumaFrames = 0
            }
        } else {
            if (avg < NIGHT_ENGAGE_LUMA) {
                lowLumaFrames++
                highLumaFrames = 0
                if (lowLumaFrames >= NIGHT_DEBOUNCE_FRAMES) {
                    _lowLightDetected.value = true
                    lowLumaFrames = 0
                }
            } else {
                lowLumaFrames = 0
            }
        }
    }

    /** Toggles the user's override for the current dark scene (moon button handler). */
    fun toggleNightModeOverride() {
        _nightModeOverriddenOff.value = !_nightModeOverriddenOff.value
    }

    private fun setLastCaptureUri(uri: Uri?) {
        _lastCaptureUri.value = uri
        viewModelScope.launch { ds.setString("camera_last_capture", uri?.toString() ?: "") }
    }

    fun switchCameraMode(newMode: CameraMode) {
        _cameraMode.value = newMode
    }

    fun flipCamera() {
        _lensFacing.value = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        resetNightModeDetection()
    }

    private fun resetNightModeDetection() {
        _lowLightDetected.value = false
        _nightModeOverriddenOff.value = false
        lowLumaFrames = 0
        highLumaFrames = 0
    }

    /**
     * Whether captures should be horizontally mirrored to match the preview. CameraX mirrors the
     * front-camera preview but saves un-mirrored by default, so selfies otherwise come out flipped.
     */
    private val mirrorCaptures: Boolean
        get() = _lensFacing.value == CameraSelector.LENS_FACING_FRONT

    fun setQrResult(text: String?) {
        _qrResult.value = text
    }

    fun setZoomRatio(ratio: Float) {
        val cam = boundCamera
        val clamped = cam?.cameraInfo?.zoomState?.value?.let {
            ratio.coerceIn(it.minZoomRatio, it.maxZoomRatio)
        } ?: ratio
        _zoomRatio.value = clamped
        cam?.cameraControl?.setZoomRatio(clamped)
    }

    fun updateZoomLevels(minZoom: Float, maxZoom: Float) {
        val levels = mutableListOf<Pair<String, Float>>()
        // Wide-angle entry only when the lens can actually zoom out past 1x.
        if (minZoom < 0.95f) {
            levels.add(formatZoomLabel(minZoom) to minZoom)
        }
        levels.add("1x" to 1f)
        for (tele in listOf(2f, 5f)) {
            if (tele <= maxZoom + 0.05f) levels.add(formatZoomLabel(tele) to tele)
        }
        _availableZoomLevels.value = levels
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun updateLocation() {
        if (!_locationEnabled.value) return
        try {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lastLocation = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to read last known location", e)
        }
    }

    suspend fun setupHighSpeedSession(): Boolean {
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()

            val cameraInfo = provider.getCameraInfo(selector)
            val capabilities = Recorder.getHighSpeedVideoCapabilities(cameraInfo)
            if (capabilities == null) {
                Log.d("SloMo", "High-speed video not supported by this camera")
                return false
            }

            val supportedQualities = capabilities.getSupportedQualities(
                androidx.camera.core.DynamicRange.SDR
            )
            Log.d("SloMo", "High-speed supported qualities: $supportedQualities")

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

            val recorder = Recorder.Builder().build()
            val videoCapture = VideoCapture.Builder(recorder)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .build()
            videoCapture.targetRotation = targetRotation
            highSpeedVideoCapture = videoCapture

            val configBuilder = HighSpeedVideoSessionConfig.Builder(videoCapture)
                .setPreview(preview)
                .setSlowMotionEnabled(true)

            val ranges = cameraInfo.getSupportedFrameRateRanges(configBuilder.build())
            Log.d("SloMo", "High-speed supported frame rate ranges: $ranges")

            val bestRange = ranges.maxByOrNull { it.upper }
            if (bestRange == null) {
                Log.e("SloMo", "No high-speed frame rate ranges available")
                return false
            }

            configBuilder.setFrameRateRange(bestRange)
            sloMoFps = bestRange.upper
            Log.d("SloMo", "High-speed session configured at ${bestRange.upper}fps, slow-mo baked in")

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner
            boundCamera = provider.bindToLifecycle(owner, selector, configBuilder.build())

            // Set anti-banding to reduce flicker under artificial light
            try {
                val cam2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(
                    boundCamera!!.cameraControl
                )
                cam2Control.setCaptureRequestOptions(
                    androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                            android.hardware.camera2.CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                        )
                        .build()
                )
            } catch (e: Exception) {
                Log.w("SloMo", "Could not set anti-banding", e)
            }

            boundCamera?.cameraInfo?.zoomState?.value?.let {
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                _zoomRatio.value = it.zoomRatio
            }
            _highSpeedActive.value = true
            true
        } catch (e: Exception) {
            Log.e("SloMo", "Failed to set up high-speed session", e)
            false
        }
    }

    /**
     * Binds a manual Preview + ImageCapture + ImageAnalysis session for the photo modes
     * (PHOTO / PORTRAIT / PANORAMA / PHOTOSPHERE / QR). ImageCapture requests the sensor's
     * maximum resolution; if the 3-stream max-res combination exceeds a device's stream-config
     * limits, it falls back to a default ImageCapture resolution.
     */
    suspend fun setupPhotoSession(): Boolean {
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()

            val previewBuilder = Preview.Builder()
            // Snapshot auto-converged AE ISO/exposure off the repeating preview requests so a
            // half-manual exposure can seed the un-set parameter.
            try {
                androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                    .setSessionCaptureCallback(aeSnapshotCallback)
            } catch (e: Exception) {
                Log.w("PhotoSession", "Could not attach AE snapshot callback", e)
            }
            val preview = previewBuilder.build()
            preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            // Ultra HDR (JPEG with a gain map) when the sensor/pipeline supports it. Queried once
            // here; the bind ladder falls back to plain JPEG if the Ultra HDR combo can't bind.
            val ultraHdrSupported = try {
                val cameraInfo = provider.getCameraInfo(selector)
                ImageCapture.getImageCaptureCapabilities(cameraInfo)
                    .supportedOutputFormats
                    .contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            } catch (e: Exception) {
                Log.w("PhotoSession", "Could not query Ultra HDR support", e)
                false
            }

            fun bind(maxRes: Boolean, ultraHdr: Boolean): Camera {
                val selectorBuilder = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                if (maxRes) {
                    // HIGHEST_AVAILABLE + PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE unlocks the
                    // full-res (maximum-resolution) sensor sizes, re-resolved per bound camera.
                    selectorBuilder.setAllowedResolutionMode(
                        ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                    )
                }
                val captureBuilder = ImageCapture.Builder()
                    .setResolutionSelector(selectorBuilder.build())
                    .setFlashMode(getImageCaptureFlashMode())
                    .setTargetRotation(targetRotation)
                if (ultraHdr) {
                    captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                }
                val capture = captureBuilder.build()
                imageCapture = capture
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                if (maxRes) {
                    // Raise the analysis stream too so the night-mode burst (collected off this
                    // stream) is as high-res as the device's 3-stream combo allows. Tied to maxRes
                    // so the default-resolution fallback keeps the analysis stream conservative if
                    // the high-res 3-stream bind is rejected.
                    analysisBuilder.setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                }
                val analysis = analysisBuilder.build()
                imageAnalysis = analysis
                return provider.bindToLifecycle(owner, selector, preview, capture, analysis)
            }

            // Fallback ladder: UltraHDR+maxres → UltraHDR+default → JPEG+default.
            boundCamera = try {
                bind(maxRes = true, ultraHdr = ultraHdrSupported)
            } catch (e: Exception) {
                Log.w("PhotoSession", "Max-res bind failed; retrying at default resolution", e)
                provider.unbindAll()
                try {
                    bind(maxRes = false, ultraHdr = ultraHdrSupported)
                } catch (e2: Exception) {
                    if (!ultraHdrSupported) throw e2
                    Log.w("PhotoSession", "Ultra HDR bind failed; falling back to plain JPEG", e2)
                    provider.unbindAll()
                    bind(maxRes = false, ultraHdr = false)
                }
            }

            boundCamera?.cameraInfo?.zoomState?.value?.let {
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                _zoomRatio.value = it.zoomRatio
            }
            readManualControlRanges()
            applyManualControls()
            _photoSessionActive.value = true
            true
        } catch (e: Exception) {
            Log.e("PhotoSession", "Failed to set up photo session", e)
            false
        }
    }

    /**
     * Binds a lean Preview + capped-resolution ImageAnalysis session for the
     * panorama and photo-sphere modes. These sweep off the analysis stream and
     * never use ImageCapture. The analysis stream is capped at ~3 MP: the pano
     * analyzer decodes every delivered frame to a Bitmap, so an uncapped max-res
     * stream janks the preview, while the 8 MP compose canvas means higher
     * per-frame resolution barely affects the stitched output.
     */
    suspend fun setupPanoramaSession(): Boolean {
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()

            val previewBuilder = Preview.Builder()
            val preview = previewBuilder.build()
            preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            // Cap the analysis stream at ~3 MP. The compose canvas is bounded to
            // 8 MP, so per-frame resolution beyond a few MP adds little to the
            // stitched output — but the analyzer converts every delivered frame to
            // a Bitmap, so a max-res stream makes that conversion (and its GC
            // churn) heavy enough to jank the preview during the sweep. ~3 MP keeps
            // it smooth. Falls back to the device-default analysis resolution if
            // this bound can't bind.
            fun bind(capped: Boolean): Camera {
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                if (capped) {
                    analysisBuilder.setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(2016, 1512), // ~3 MP
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                }
                val analysis = analysisBuilder.build()
                imageAnalysis = analysis
                imageCapture = null // No ImageCapture in this session.
                return provider.bindToLifecycle(owner, selector, preview, analysis)
            }

            boundCamera = try {
                bind(capped = true)
            } catch (e: Exception) {
                Log.w("PhotoSession", "Capped panorama bind failed; retrying at default resolution", e)
                provider.unbindAll()
                bind(capped = false)
            }

            boundCamera?.cameraInfo?.zoomState?.value?.let {
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                _zoomRatio.value = it.zoomRatio
            }
            _photoSessionActive.value = true
            true
        } catch (e: Exception) {
            Log.e("PhotoSession", "Failed to set up panorama session", e)
            false
        }
    }

    /** Reads the bound sensor's ISO range → stop list for the manual ISO control. */
    private fun readManualControlRanges() {
        val cam = boundCamera ?: return
        try {
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
            val isoRange = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            _isoStops.value = if (isoRange != null) {
                listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
                    .filter { it in isoRange.lower..isoRange.upper }
                    .ifEmpty { listOf(isoRange.lower, isoRange.upper) }
            } else emptyList()
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to read manual control ranges", e)
        }
    }

    suspend fun setupVideoSession(): Boolean {
        return try {
            val provider = ProcessCameraProvider.awaitInstance(app)
            cameraProvider = provider
            provider.unbindAll()

            val selector = CameraSelector.Builder()
                .requireLensFacing(_lensFacing.value)
                .build()
            val cameraInfo = provider.getCameraInfo(selector)

            val selectedCodec = _videoCodec.value
            // Respect user setting with fallback: AV1 > HEVC > AVC priority for availability check.
            // If selected codec isn't available, fall back to next best available.
            val useAv1 = selectedCodec == VideoCodec.AV1 &&
                CodecSupport.isHardwareAv1EncoderAvailable && av1SupportedByCamera(cameraInfo)
            val useHevc = !useAv1 && selectedCodec != VideoCodec.AVC &&
                CodecSupport.isHevcEncoderAvailable && hevcSupportedByCamera(cameraInfo)
            // All codecs use AAC audio. Opus disabled to avoid CameraX raw-Opus-CSD muxing bug.
            val useOpus = false
            // HLG10 (10-bit HDR) when the camera's video pipeline supports it. Uses the default
            // HEVC/H.264 codec (AV1 stays off). Preview and VideoCapture must share the dynamic
            // range or the bind fails, so both are gated together.
            val hlgSupported = hlgSupportedByCamera(cameraInfo)
            Log.d("VideoSession", "Codec selection: av1=$useAv1, hevc=$useHevc, opus=$useOpus, hlg10=$hlgSupported")

            // Cinematic mode enables video stabilization; all video modes always record at max
            // quality/fps (no UI picker).
            val cinematic = _cameraMode.value == CameraMode.CINEMATIC
            val bestFpsRange = highestFpsRange(cameraInfo)
            val stabilizationMode = if (cinematic) preferredStabilizationMode(cameraInfo) else null
            Log.d("VideoSession", "Video tuning: fps=$bestFpsRange, stabilization=$stabilizationMode")

            // Prefer UHD, then FHD, then HD, falling back to the next lower supported quality.
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            fun bind(av1: Boolean, hevc: Boolean, opus: Boolean, hlg: Boolean, snapshot: Boolean): Camera {
                val dynamicRange = if (hlg) androidx.camera.core.DynamicRange.HLG_10_BIT
                    else androidx.camera.core.DynamicRange.SDR
                val previewBuilder = Preview.Builder()
                    .setDynamicRange(dynamicRange)
                applyVideoCaptureRequestOptions(previewBuilder, bestFpsRange, stabilizationMode)
                val preview = previewBuilder.build()
                preview.setSurfaceProvider { request -> _surfaceRequest.value = request }
                val recorderBuilder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                if (av1) recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AV1)
                else if (hevc) recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                if (opus) recorderBuilder.setAudioMimeType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                val captureBuilder = VideoCapture.Builder(recorderBuilder.build())
                    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                    .setDynamicRange(dynamicRange)
                applyVideoCaptureRequestOptions(captureBuilder, bestFpsRange, stabilizationMode)
                val capture = captureBuilder.build()
                capture.targetRotation = targetRotation
                videoCapture = capture
                recordingWithAv1 = av1
                recordingWithHevc = hevc
                return if (snapshot) {
                    // Extra ImageCapture use case enables taking a still while recording (SDR JPEG).
                    val still = ImageCapture.Builder()
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .setTargetRotation(targetRotation)
                        .build()
                    imageCapture = still
                    provider.bindToLifecycle(owner, selector, preview, capture, still)
                } else {
                    imageCapture = null
                    provider.bindToLifecycle(owner, selector, preview, capture)
                }
            }

            // Bind ladder: prefer HDR + snapshot, then drop the snapshot use case, then drop HDR.
            val attempts = buildList {
                add(hlgSupported to true)
                add(hlgSupported to false)
                if (hlgSupported) {
                    add(false to true)
                    add(false to false)
                }
            }
            var bound: Camera? = null
            var lastError: Exception? = null
            for ((hlg, snapshot) in attempts) {
                try {
                    provider.unbindAll()
                    bound = bind(useAv1, useHevc, useOpus, hlg, snapshot)
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w("VideoSession", "Video bind failed (hlg=$hlg, snapshot=$snapshot); trying next", e)
                }
            }
            boundCamera = bound ?: throw (lastError ?: IllegalStateException("Video session bind failed"))
            _videoSnapshotSupported.value = imageCapture != null

            boundCamera?.cameraInfo?.zoomState?.value?.let {
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                _zoomRatio.value = it.zoomRatio
            }
            _videoSessionActive.value = true
            true
        } catch (e: Exception) {
            Log.e("VideoSession", "Failed to set up video session", e)
            false
        }
    }

    private fun av1SupportedByCamera(cameraInfo: androidx.camera.core.CameraInfo): Boolean = try {
        val caps = Recorder.getVideoCapabilities(cameraInfo, MediaFormat.MIMETYPE_VIDEO_AV1)
        caps?.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)?.isNotEmpty() == true
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query AV1 video capabilities", e)
        false
    }

    private fun hevcSupportedByCamera(cameraInfo: androidx.camera.core.CameraInfo): Boolean = try {
        val caps = Recorder.getVideoCapabilities(cameraInfo, MediaFormat.MIMETYPE_VIDEO_HEVC)
        caps?.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)?.isNotEmpty() == true
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query HEVC video capabilities", e)
        false
    }

    private fun hlgSupportedByCamera(cameraInfo: androidx.camera.core.CameraInfo): Boolean = try {
        Recorder.getVideoCapabilities(cameraInfo)
            .supportedDynamicRanges
            .contains(androidx.camera.core.DynamicRange.HLG_10_BIT)
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query HLG10 dynamic-range support", e)
        false
    }

    /** Highest supported AE target frame-rate range for maxing video fps; null if unavailable. */
    private fun highestFpsRange(cameraInfo: androidx.camera.core.CameraInfo): android.util.Range<Int>? = try {
        androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )
            ?.maxByOrNull { it.upper }
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query supported frame-rate ranges", e)
        null
    }

    /**
     * Preferred video stabilization mode for Cinematic: preview-stabilization ("EIS") when the
     * device lists it, else on-mode, else null (unsupported → stabilization is skipped).
     */
    private fun preferredStabilizationMode(cameraInfo: androidx.camera.core.CameraInfo): Int? = try {
        val modes = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            )?.toList() ?: emptyList()
        when {
            modes.contains(
                android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            ) -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            modes.contains(
                android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ) -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else -> null
        }
    } catch (e: Exception) {
        Log.w("VideoSession", "Could not query video stabilization modes", e)
        null
    }

    /** Applies max-fps + (optional) stabilization capture options onto a Preview/VideoCapture builder. */
    private fun <T> applyVideoCaptureRequestOptions(
        builder: androidx.camera.core.ExtendableBuilder<T>,
        fpsRange: android.util.Range<Int>?,
        stabilizationMode: Int?
    ) {
        try {
            val extender = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
            if (fpsRange != null) {
                extender.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange
                )
            }
            if (stabilizationMode != null) {
                extender.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    stabilizationMode
                )
            }
        } catch (e: Exception) {
            Log.w("VideoSession", "Could not apply Camera2 video capture options", e)
        }
    }

    /** Tears down whatever session is currently bound and clears the shared preview surface. */
    fun teardownSession() {
        currentRecording?.stop()
        currentRecording = null
        highSpeedRecording?.stop()
        highSpeedRecording = null
        imageAnalysis?.clearAnalyzer()
        currentAnalyzer = null
        sessionLifecycleOwner?.destroy()
        sessionLifecycleOwner = null
        cameraProvider?.unbindAll()
        boundCamera = null
        imageCapture = null
        imageAnalysis = null
        videoCapture = null
        highSpeedVideoCapture = null
        cameraProvider = null
        _surfaceRequest.value = null
        _photoSessionActive.value = false
        _highSpeedActive.value = false
        _videoSessionActive.value = false
        _videoSnapshotSupported.value = false
        _focusLocked.value = false
        resetNightModeDetection()
        resetManualControls()
        clearMotionFrames()
    }

    // --- Unified manual session control wiring (targets the single bound camera) ---

    fun startFocusAndMetering(action: FocusMeteringAction) {
        // A fresh tap clears any existing AE/AF lock.
        _focusLocked.value = false
        boundCamera?.cameraControl?.startFocusAndMetering(action)
    }

    /**
     * Locks focus + exposure at the metered point by disabling auto-cancel, so 3A stays put until
     * the user taps again (long-press-to-lock). Sets [focusLocked] for the on-screen indicator.
     */
    fun lockFocusAndMetering(action: FocusMeteringAction) {
        val cam = boundCamera ?: return
        cam.cameraControl.startFocusAndMetering(action)
        _focusLocked.value = true
    }

    fun clearFocusLock() {
        boundCamera?.cameraControl?.cancelFocusAndMetering()
        _focusLocked.value = false
    }

    fun enableTorch(enabled: Boolean) {
        boundCamera?.cameraControl?.enableTorch(enabled)
    }

    fun applyExposureCompensation(value: Float) {
        val cam = boundCamera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        val index = (value * range.upper).toInt().coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    /** Pushes the current flash mode onto the bound ImageCapture (runtime-mutable, no rebind). */
    fun applyImageCaptureFlashMode() {
        imageCapture?.flashMode = getImageCaptureFlashMode()
    }

    /** Updates the capture orientation from the device's physical rotation (Surface.ROTATION_*). */
    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
        highSpeedVideoCapture?.targetRotation = rotation
    }

    /** Swaps the analyzer on the bound ImageAnalysis without rebinding. */
    fun setImageAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
        val analysis = imageAnalysis ?: return
        currentAnalyzer = analyzer
        if (analyzer == null) analysis.clearAnalyzer()
        else analysis.setAnalyzer(ContextCompat.getMainExecutor(app), analyzer)
    }

    private fun startRecordingTimer() {
        _isRecording.value = true
        _recordingPaused.value = false
        _recordingDurationSec.value = 0
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                if (!_recordingPaused.value) _recordingDurationSec.value += 1
            }
        }
    }

    private fun stopRecordingTimer() {
        _isRecording.value = false
        _recordingPaused.value = false
        recordingTimerJob?.cancel()
        _recordingDurationSec.value = 0
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun toggleHighSpeedRecording() {
        // Cancel countdown if active
        if (_timerCountdown.value > 0) {
            timerCountdownJob?.cancel()
            timerCountdownJob = null
            _timerCountdown.value = 0
            return
        }

        if (_isRecording.value) {
            highSpeedRecording?.stop()
            highSpeedRecording = null
            stopRecordingTimer()
            return
        }

        val timer = _timerDuration.value
        if (timer.seconds > 0) {
            // Start countdown before recording
            timerCountdownJob = viewModelScope.launch {
                for (i in timer.seconds downTo 1) {
                    _timerCountdown.value = i
                    delay(1000)
                }
                _timerCountdown.value = 0
                timerCountdownJob = null
                startHighSpeedRecording()
            }
            return
        }

        startHighSpeedRecording()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startHighSpeedRecording() {
        val videoCapture = highSpeedVideoCapture ?: return

        val contentValues = MediaStoreSaver.videoValues("SLOMO_${MediaStoreSaver.timestamp()}")

        val outputOptions = MediaStoreOutputOptions.Builder(
            app.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        startRecordingTimer()

        Log.d("SloMo", "Starting high-speed recording at ${sloMoFps}fps")

        highSpeedRecording = videoCapture.output
            .prepareRecording(app, outputOptions)
            .start(ContextCompat.getMainExecutor(app)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    stopRecordingTimer()
                    if (event.hasError()) {
                        Log.e("SloMo", "Recording error: ${event.error} - ${event.cause?.message}")
                    } else {
                        Log.d("SloMo", "High-speed recording saved: ${event.outputResults.outputUri}")
                        setLastCaptureUri(event.outputResults.outputUri)
                    }
                }
            }
    }

    // --- Standard recording ---

    fun takePhoto() {
        val timer = _timerDuration.value
        if (timer.seconds > 0) {
            viewModelScope.launch {
                for (i in timer.seconds downTo 1) {
                    _timerCountdown.value = i
                    delay(1000)
                }
                _timerCountdown.value = 0
                capturePhoto()
            }
        } else {
            capturePhoto()
        }
    }

    private fun capturePhoto() {
        if (imageCapture == null) return
        when {
            // Multi-frame night capture only when night mode is active and exposure is fully auto.
            nightModeActive.value && isExposureAuto() -> captureNightPhoto()
            // Motion Photo for plain PHOTO captures (no warmth/shadows bake, not capturing for a caller).
            _cameraMode.value == CameraMode.PHOTO && !captureForResult &&
                _warmth.value == 0f && _shadows.value == 0f -> captureMotionPhoto()
            else -> captureSinglePhoto()
        }
    }

    /**
     * Starts a press-and-hold burst: standard single-frame captures fired back-to-back (one in
     * flight at a time) with `IMG_<ts>_BURSTn` names, until [stopBurst] or [BURST_MAX] is reached.
     * Uses the plain capture path (no night/manual special-casing).
     */
    fun startBurst() {
        if (_burstActive.value) return
        val capture = imageCapture ?: return
        _burstActive.value = true
        _burstCount.value = 0
        val ts = MediaStoreSaver.timestamp()

        fun shootNext(n: Int) {
            if (!_burstActive.value || n > BURST_MAX) {
                _burstActive.value = false
                return
            }
            val values = MediaStoreSaver.imageValues("IMG_${ts}_BURST${n}.jpg")
            val metadata = ImageCapture.Metadata().apply {
                if (_locationEnabled.value) location = lastLocation
                isReversedHorizontal = mirrorCaptures
            }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                app.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).setMetadata(metadata).build()

            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        _burstCount.value = n
                        outputFileResults.savedUri?.let { setLastCaptureUri(it) }
                        shootNext(n + 1)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraViewModel", "Burst frame $n failed", exception)
                        shootNext(n + 1)
                    }
                }
            )
        }
        shootNext(1)
    }

    fun stopBurst() {
        _burstActive.value = false
    }

    /** Appends an analysis frame to the Motion-Photo ring buffer, trimming by age then count. */
    fun addMotionFrame(bitmap: Bitmap, timestampNanos: Long, rotationDegrees: Int) {
        synchronized(motionLock) {
            motionFrames.addLast(MotionFrame(bitmap, timestampNanos, rotationDegrees))
            val cutoff = timestampNanos - MOTION_WINDOW_NANOS
            while (motionFrames.size > 1 && motionFrames.first().timestampNanos < cutoff) {
                motionFrames.removeFirst().bitmap.recycle()
            }
            while (motionFrames.size > MOTION_MAX_FRAMES) {
                motionFrames.removeFirst().bitmap.recycle()
            }
        }
    }

    private fun drainMotionFrames(): List<MotionFrame> = synchronized(motionLock) {
        val list = motionFrames.toList()
        motionFrames.clear()
        list
    }

    private fun clearMotionFrames() = synchronized(motionLock) {
        motionFrames.forEach { it.bitmap.recycle() }
        motionFrames.clear()
    }

    /**
     * Captures a Motion Photo: the still (captured in-memory so its bytes can carry the trailer) plus
     * the buffered ring-buffer frames encoded to a short MP4 and appended as a Google Motion Photo
     * trailer. Falls back to saving the plain still if no frames are buffered or encoding fails.
     * The still may be Ultra HDR (gain map preserved); the appended clip is SDR at analysis resolution.
     */
    private fun captureMotionPhoto() {
        val capture = imageCapture ?: return
        _isCapturing.value = true
        capture.takePicture(
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val degrees = image.imageInfo.rotationDegrees
                    val jpegBytes = try {
                        image.planes[0].buffer.let { buf ->
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        }
                    } finally {
                        image.close()
                    }
                    val frames = drainMotionFrames()
                    viewModelScope.launch {
                        val uri = withContext(Dispatchers.Default) {
                            assembleAndSaveMotionPhoto(jpegBytes, frames, degrees)
                        }
                        frames.forEach { it.bitmap.recycle() }
                        _isCapturing.value = false
                        if (uri != null) setLastCaptureUri(uri)
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraViewModel", "Motion Photo capture failed; falling back to still", exception)
                    _isCapturing.value = false
                    captureSinglePhoto()
                }
            }
        )
    }

    private fun assembleAndSaveMotionPhoto(
        jpegBytes: ByteArray,
        frames: List<MotionFrame>,
        degrees: Int
    ): Uri? {
        val values = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
        // Only frames matching the newest frame's dimensions are encoded (a rebind can change size).
        val sized = frames.takeIf { it.isNotEmpty() }?.let { list ->
            val w = list.last().bitmap.width
            val h = list.last().bitmap.height
            list.filter { it.bitmap.width == w && it.bitmap.height == h }
        }.orEmpty()

        val bytes = if (sized.size >= 2) {
            val tmp = java.io.File(app.cacheDir, "motion_${System.currentTimeMillis()}.mp4")
            val spanNs = (sized.last().timestampNanos - sized.first().timestampNanos).coerceAtLeast(1)
            val fps = (sized.size * 1_000_000_000.0 / spanNs).roundToInt().coerceIn(5, 30)
            val ok = MotionPhotoEncoder.encode(
                sized.map { it.bitmap }, tmp, sized.last().rotationDegrees, fps
            )
            if (ok && tmp.exists()) {
                val mp4 = tmp.readBytes()
                tmp.delete()
                MotionPhotoWriter.assemble(jpegBytes, mp4)
            } else {
                tmp.delete()
                jpegBytes
            }
        } else {
            jpegBytes
        }
        return MediaStoreSaver.saveJpegBytes(app.contentResolver, values, bytes)
    }

    private fun captureSinglePhoto() {
        val capture = imageCapture ?: return
        _isCapturing.value = true
        val contentValues = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")

        val metadata = ImageCapture.Metadata().apply {
            if (_locationEnabled.value) location = lastLocation
            isReversedHorizontal = mirrorCaptures
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            app.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).setMetadata(metadata).build()

        val stop = EXPOSURE_TIME_STOPS[_exposureTimeIndex.value]
        // Manual shutter/ISO are already applied live via applyManualControls(); the only transient
        // per-capture override here is the night-mode emulation (fully-auto exposure + night active).
        val nightExposure = if (nightModeActive.value && isExposureAuto())
            computeNightExposure() else null
        // Used only to drive the long-exposure countdown overlay.
        val exposureNanos = stop.nanos ?: nightExposure?.nanos
        val nightIso = nightExposure?.iso
        val cam2Control = try {
            boundCamera?.cameraControl?.let {
                androidx.camera.camera2.interop.Camera2CameraControl.from(it)
            }
        } catch (e: Exception) { Log.w("CameraViewModel", "Camera2 control unavailable", e); null }

        fun restoreAfterNight() {
            // Undo the transient night override by re-asserting the (auto) manual-control state.
            if (nightExposure != null) applyManualControls()
        }

        fun doCapture() {
            if (exposureNanos != null && exposureNanos >= 250_000_000L) {
                startLongExposureCountdown(exposureNanos)
            }
            fun finishCapture(uri: Uri?) {
                _isCapturing.value = false
                stopLongExposureCountdown()
                restoreAfterNight()
                if (uri != null) setLastCaptureUri(uri)
            }

            val warmth = _warmth.value
            val shadows = _shadows.value
            val mirror = mirrorCaptures
            if (warmth != 0f || shadows != 0f) {
                // The warmth/shadows adjustment only lives in the preview RenderEffect, so bake
                // it into the pixels here: capture in-memory, apply the same color matrix, then
                // re-encode. Re-encoding drops the JPEG's EXIF, so we copy it back from the
                // original frame (plus GPS and the orientation tag) to match the normal path.
                // Caveat: this processed path is always SDR JPEG — decoding to an ARGB_8888 bitmap
                // discards any Ultra HDR gain map, so warmth/shadows captures lose HDR even when
                // Ultra HDR is otherwise active. Normal (unprocessed) captures keep the gain map.
                capture.takePicture(
                    ContextCompat.getMainExecutor(app),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val degrees = image.imageInfo.rotationDegrees
                            val sourceJpeg = try {
                                image.planes[0].buffer.let { buf ->
                                    ByteArray(buf.remaining()).also { buf.get(it) }
                                }
                            } finally {
                                image.close()
                            }
                            viewModelScope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    val decoded = BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size)
                                    val adjusted = applyColorAdjustments(decoded, warmth, shadows, mirror)
                                    val values = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
                                    MediaStoreSaver.saveBitmap(app.contentResolver, values, adjusted)
                                        ?.also { writeCaptureExif(it, sourceJpeg, degrees) }
                                        .also { adjusted.recycle() }
                                }
                                finishCapture(uri)
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraViewModel", "Adjusted capture failed", exception)
                            finishCapture(null)
                        }
                    }
                )
                return
            }

            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        finishCapture(outputFileResults.savedUri)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        finishCapture(null)
                    }
                }
            )
        }

        if (nightExposure != null && cam2Control != null) {
            try {
                val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                        nightExposure.nanos
                    )
                if (nightIso != null) {
                    options.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,
                        nightIso
                    )
                }
                cam2Control.setCaptureRequestOptions(options.build())
                    .addListener({ doCapture() }, ContextCompat.getMainExecutor(app))
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to set night exposure", e)
                doCapture()
            }
        } else {
            doCapture()
        }
    }

    /**
     * Multi-frame night capture: locks the sensor to a per-frame night exposure/ISO, collects a
     * burst off the ImageAnalysis stream, then aligns + merges + brightens it (via
     * [NightCaptureEngine]) and saves. Falls back to the single long-exposure capture if the burst
     * is empty or the merge fails, so the user always gets a shot.
     */
    private fun captureNightPhoto() {
        _isCapturing.value = true
        val perFrame = computeNightExposure(NIGHT_BURST_PER_FRAME_NANOS)
        // The countdown overlay shows the total burst duration.
        startLongExposureCountdown(perFrame.nanos * NightCaptureEngine.NIGHT_BURST_COUNT)

        captureNightBurst(perFrame) { frames ->
            if (frames.isEmpty()) {
                Log.w("CameraViewModel", "Night burst produced no frames; falling back to single capture")
                stopLongExposureCountdown()
                captureSinglePhoto()
                return@captureNightBurst
            }
            viewModelScope.launch {
                val uri = withContext(Dispatchers.Default) {
                    val merged = NightCaptureEngine.merge(frames)
                    frames.forEach { it.recycle() }
                    merged?.let { bmp ->
                        val values = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
                        MediaStoreSaver.saveBitmap(app.contentResolver, values, bmp)
                            .also { bmp.recycle() }
                    }
                }
                if (uri != null) {
                    // Merged pixels are already upright/mirrored, so the orientation tag is normal.
                    withContext(Dispatchers.IO) { writeCaptureExif(uri, null, 0) }
                    _isCapturing.value = false
                    stopLongExposureCountdown()
                    setLastCaptureUri(uri)
                } else {
                    Log.w("CameraViewModel", "Night merge failed; falling back to single capture")
                    stopLongExposureCountdown()
                    captureSinglePhoto()
                }
            }
        }
    }

    /**
     * Locks 3A to [exposure], temporarily swaps the analyzer for a frame collector that gathers the
     * next [NightCaptureEngine.NIGHT_BURST_COUNT] frames as upright (front-mirrored) bitmaps, then
     * restores auto 3A and the previous analyzer and invokes [onDone] with the collected frames
     * (empty if the analysis stream is unavailable).
     */
    private fun captureNightBurst(exposure: NightExposure, onDone: (List<Bitmap>) -> Unit) {
        if (imageAnalysis == null) {
            onDone(emptyList())
            return
        }
        val cam2Control = try {
            boundCamera?.cameraControl?.let {
                androidx.camera.camera2.interop.Camera2CameraControl.from(it)
            }
        } catch (e: Exception) { Log.w("CameraViewModel", "Camera2 control unavailable", e); null }

        val previousAnalyzer = currentAnalyzer
        val mirror = mirrorCaptures
        val collected = mutableListOf<Bitmap>()
        var finished = false

        fun restore() {
            if (cam2Control != null) {
                try {
                    cam2Control.setCaptureRequestOptions(
                        androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME)
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY)
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE)
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK)
                            .build()
                    )
                } catch (e: Exception) {
                    Log.w("CameraViewModel", "Failed to restore auto 3A after night burst", e)
                }
            }
            setImageAnalyzer(previousAnalyzer)
        }

        val collector = ImageAnalysis.Analyzer { imageProxy ->
            if (!finished && collected.size < NightCaptureEngine.NIGHT_BURST_COUNT) {
                try {
                    val raw = imageProxy.toBitmap()
                    val matrix = Matrix().apply {
                        postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                        if (mirror) postScale(-1f, 1f)
                    }
                    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    if (upright !== raw) raw.recycle()
                    collected.add(upright)
                } catch (e: Exception) {
                    Log.w("CameraViewModel", "Failed to collect night burst frame", e)
                }
            }
            imageProxy.close()
            if (!finished && collected.size >= NightCaptureEngine.NIGHT_BURST_COUNT) {
                finished = true
                restore()
                onDone(collected.toList())
            }
        }

        if (cam2Control != null) {
            try {
                val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                        exposure.nanos
                    )
                    // Lock white balance so frames merge without color drift across the burst.
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, true
                    )
                exposure.iso?.let {
                    options.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, it
                    )
                }
                cam2Control.setCaptureRequestOptions(options.build())
                    .addListener({ setImageAnalyzer(collector) }, ContextCompat.getMainExecutor(app))
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to set night exposure for burst", e)
                setImageAnalyzer(collector)
            }
        } else {
            setImageAnalyzer(collector)
        }
    }

    private data class NightExposure(val nanos: Long, val iso: Int?)

    /**
     * Derives a night exposure/ISO from the bound sensor's characteristics: clamps [targetNanos]
     * into the sensor's exposure-time range and picks a high fraction of its sensitivity range.
     * Falls back to [targetNanos] (and auto ISO) if the characteristics are unavailable.
     */
    private fun computeNightExposure(targetNanos: Long = NIGHT_TARGET_EXPOSURE_NANOS): NightExposure {
        val fallback = NightExposure(targetNanos, null)
        return try {
            val cam = boundCamera ?: return fallback
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
            val expRange = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
            val isoRange = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val nanos = expRange?.let {
                targetNanos.coerceIn(it.lower, it.upper)
            } ?: targetNanos
            val iso = isoRange?.let {
                (it.lower + ((it.upper - it.lower) * NIGHT_ISO_FRACTION).roundToInt())
                    .coerceIn(it.lower, it.upper)
            }
            NightExposure(nanos, iso)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to read sensor ranges for night mode", e)
            fallback
        }
    }

    /**
     * Capture path for the system IMAGE_CAPTURE intent. If the caller supplied an EXTRA_OUTPUT
     * Uri, the full-resolution JPEG is written there and [onSaved] is invoked with a null
     * thumbnail (the documented "no data extra needed" contract). Otherwise a downscaled
     * thumbnail Bitmap is returned for the result "data" extra.
     */
    fun capturePhotoForResult(onSaved: (Bitmap?) -> Unit, onError: () -> Unit) {
        val capture = imageCapture ?: return onError()
        _isCapturing.value = true
        val executor = ContextCompat.getMainExecutor(app)
        val outputUri = resultOutputUri

        if (outputUri != null) {
            val outputStream = try {
                app.contentResolver.openOutputStream(outputUri)
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Could not open EXTRA_OUTPUT for writing", e)
                null
            }
            if (outputStream == null) {
                _isCapturing.value = false
                return onError()
            }
            val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = mirrorCaptures }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputStream)
                .setMetadata(metadata)
                .build()
            capture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    _isCapturing.value = false
                    onSaved(null)
                }
                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    Log.e("CameraViewModel", "IMAGE_CAPTURE to EXTRA_OUTPUT failed", exception)
                    onError()
                }
            })
        } else {
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    _isCapturing.value = false
                    val mirror = mirrorCaptures
                    val thumbnail = try {
                        downscaledThumbnail(image, mirror)
                    } finally {
                        image.close()
                    }
                    onSaved(thumbnail)
                }
                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    Log.e("CameraViewModel", "IMAGE_CAPTURE thumbnail capture failed", exception)
                    onError()
                }
            })
        }
    }

    /** Rotates the captured frame upright (mirroring for the front camera) and scales it down for the result "data" thumbnail. */
    private fun downscaledThumbnail(image: ImageProxy, mirror: Boolean): Bitmap {
        val raw = image.toBitmap()
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        val maxSide = 512
        val scale = maxSide.toFloat() / maxOf(upright.width, upright.height)
        if (scale >= 1f) return upright
        return Bitmap.createScaledBitmap(
            upright,
            (upright.width * scale).roundToInt(),
            (upright.height * scale).roundToInt(),
            true
        )
    }

    /**
     * Bakes the warmth/shadows color matrix into [src] (and horizontally mirrors it when [mirror]
     * is set, for front-camera parity with the preview), returning a new bitmap and recycling
     * [src]. Rotation is intentionally left to the EXIF orientation tag (see [writeCaptureExif]),
     * mirroring how the normal ImageCapture path stores orientation without rotating pixels.
     */
    private fun applyColorAdjustments(src: Bitmap, warmth: Float, shadows: Float, mirror: Boolean): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(buildColorAdjustmentMatrix(warmth, shadows))
        }
        val canvas = Canvas(out)
        if (mirror) canvas.scale(-1f, 1f, src.width / 2f, src.height / 2f)
        canvas.drawBitmap(src, 0f, 0f, paint)
        src.recycle()
        return out
    }

    /**
     * Restores the EXIF that re-encoding a processed bitmap dropped: copies the original frame's
     * metadata tags (when [sourceJpeg] is provided), writes the orientation for [rotationDegrees],
     * and stamps GPS when location is enabled. For the night merge there is no single source frame,
     * so [sourceJpeg] is null and only orientation + GPS are written.
     */
    private fun writeCaptureExif(uri: Uri, sourceJpeg: ByteArray?, rotationDegrees: Int) {
        try {
            val source = sourceJpeg?.let { ExifInterface(ByteArrayInputStream(it)) }
            app.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val dest = ExifInterface(pfd.fileDescriptor)
                source?.let { src ->
                    EXIF_TAGS_TO_COPY.forEach { tag ->
                        src.getAttribute(tag)?.let { dest.setAttribute(tag, it) }
                    }
                }
                dest.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    when (rotationDegrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }.toString()
                )
                if (_locationEnabled.value) lastLocation?.let { dest.setGpsInfo(it) }
                dest.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to write EXIF for adjusted capture", e)
        }
    }

    private fun startLongExposureCountdown(nanos: Long) {
        val durationMs = nanos / 1_000_000
        _longExposureProgress.value = 1f
        _longExposureRemaining.value = formatExposureRemaining(durationMs)
        longExposureTimerJob?.cancel()
        longExposureTimerJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                val remaining = (durationMs - elapsed).coerceAtLeast(0)
                _longExposureProgress.value = remaining.toFloat() / durationMs
                _longExposureRemaining.value = formatExposureRemaining(remaining)
                if (remaining <= 0) break
                delay(50)
            }
        }
    }

    private fun stopLongExposureCountdown() {
        longExposureTimerJob?.cancel()
        longExposureTimerJob = null
        _longExposureProgress.value = 0f
        _longExposureRemaining.value = ""
    }

    private fun formatExposureRemaining(ms: Long): String {
        val seconds = ms / 1000f
        return "%.1fs".format(seconds)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun toggleRecording() {
        // Cancel countdown if active
        if (_timerCountdown.value > 0) {
            timerCountdownJob?.cancel()
            timerCountdownJob = null
            _timerCountdown.value = 0
            return
        }

        if (_isRecording.value) {
            currentRecording?.stop()
            currentRecording = null
            stopRecordingTimer()
            return
        }

        val timer = _timerDuration.value
        if (timer.seconds > 0) {
            // Start countdown before recording
            timerCountdownJob = viewModelScope.launch {
                for (i in timer.seconds downTo 1) {
                    _timerCountdown.value = i
                    delay(1000)
                }
                _timerCountdown.value = 0
                timerCountdownJob = null
                startRecording()
            }
            return
        }

        startRecording()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return

        val timestamp = MediaStoreSaver.timestamp()
        val prefix = when (_cameraMode.value) {
            CameraMode.TIMELAPSE -> "TL"
            CameraMode.CINEMATIC -> "CINE"
            else -> "VID"
        }
        val contentValues = MediaStoreSaver.videoValues("${prefix}_$timestamp")

        val cacheFile = java.io.File(app.cacheDir, "VID_$timestamp.mp4")
        val outputOptions = FileOutputOptions.Builder(cacheFile).build()
        val recordingMode = _cameraMode.value

        startRecordingTimer()

        var pending = capture.output.prepareRecording(app, outputOptions)
        val audioEnabled = recordingMode == CameraMode.VIDEO || recordingMode == CameraMode.CINEMATIC
        if (audioEnabled) {
            pending = pending.withAudioEnabled()
        }
        currentRecording = pending.start(ContextCompat.getMainExecutor(app)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                stopRecordingTimer()
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (!cacheFile.exists()) return@launch
                    val fileToSave = when (recordingMode) {
                        CameraMode.TIMELAPSE -> {
                            val processed = java.io.File(app.cacheDir, "TL_$timestamp.mp4")
                            try {
                                VideoProcessor.adjustSpeed(cacheFile, processed, 8f)
                                cacheFile.delete()
                                processed
                            } catch (e: Exception) {
                                // MediaMuxer can reject an av01 track (or an oversized keyframe)
                                // on some devices; keep the raw recording rather than crash.
                                Log.e("CameraViewModel", "Timelapse remux failed; saving unprocessed", e)
                                processed.delete()
                                cacheFile
                            }
                        }
                        else -> cacheFile
                    }
                    MediaStoreSaver.saveVideoFile(app.contentResolver, contentValues, fileToSave)?.let {
                        setLastCaptureUri(it)
                    }
                    fileToSave.delete()
                }
            }
        }
        // Apply the current mic-mute state to the freshly-started recording.
        if (audioEnabled) {
            try {
                currentRecording?.mute(_micMuted.value)
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to apply initial mic mute", e)
            }
        }
    }

    /** Pauses or resumes the active recording (video/cinematic). No-op if not recording. */
    fun togglePauseRecording() {
        val recording = currentRecording ?: return
        try {
            if (_recordingPaused.value) {
                recording.resume()
                _recordingPaused.value = false
            } else {
                recording.pause()
                _recordingPaused.value = true
            }
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to pause/resume recording", e)
        }
    }

    /** Toggles mic mute; applies live to the active recording. Persisted for the next session. */
    fun toggleMicMuted() {
        _micMuted.value = !_micMuted.value
        try {
            currentRecording?.mute(_micMuted.value)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to toggle mic mute", e)
        }
        viewModelScope.launch { ds.setString("camera_mic_muted", _micMuted.value.toString()) }
    }

    /**
     * Captures a still while recording (video snapshot), using the ImageCapture bound alongside the
     * video session. No-op if the device couldn't bind the extra ImageCapture use case.
     */
    fun captureVideoSnapshot() {
        val capture = imageCapture ?: return
        val contentValues = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
        val metadata = ImageCapture.Metadata().apply {
            if (_locationEnabled.value) location = lastLocation
            isReversedHorizontal = mirrorCaptures
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            app.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).setMetadata(metadata).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    outputFileResults.savedUri?.let { setLastCaptureUri(it) }
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraViewModel", "Video snapshot failed", exception)
                }
            }
        )
    }

    fun startPanorama() = panoramaEngine.startSweep()

    fun stopPanorama() = finishPanoramaSweep()

    fun startPhotosphere() = panoramaEngine.startSweep(fullSphere = true)

    fun stopPhotosphere() = finishPanoramaSweep()

    @Volatile
    private var isFinishingPano = false

    private fun finishPanoramaSweep() {
        if (isFinishingPano) return
        isFinishingPano = true
        panoramaEngine.stopSweep()
        viewModelScope.launch {
            panoramaEngine.stitch()?.let { (jpeg, info) ->
                panoramaEngine.saveToMediaStore(jpeg, info)
            }
            panoramaEngine.reset()
            isFinishingPano = false
        }
    }

    fun getImageCaptureFlashMode(): Int = when (_flashMode.value) {
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    }

    override fun onCleared() {
        unregisterLevelSensor()
        super.onCleared()
    }
}

private class ManualLifecycleOwner : LifecycleOwner {
    private val registry = androidx.lifecycle.LifecycleRegistry(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry

    fun start() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
    }
}
