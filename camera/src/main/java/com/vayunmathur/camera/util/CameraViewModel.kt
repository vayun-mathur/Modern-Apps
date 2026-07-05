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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.camera.lifecycle.awaitInstance
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

enum class CameraMode { PHOTO, PORTRAIT, PANORAMA, PHOTOSPHERE, VIDEO, SLOW_MO, TIMELAPSE }
enum class FlashMode { ON, OFF, AUTO }
enum class TimerDuration(val seconds: Int) { NONE(0), THREE(3), FIVE(5), TEN(10) }
enum class AspectRatioOption(val label: String) { RATIO_16_9("16:9"), RATIO_4_3("4:3"), RATIO_1_1("1:1") }

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
        /**
         * AV1/Opus recording is disabled: CameraX's Recorder muxes an incomplete `av1C`
         * (empty configOBUs — no sequence-header OBU) and Android raw Opus CSD, producing
         * files the system thumbnailer and ExoPlayer can't decode (0x0 playback, no
         * thumbnail) even on hardware that supports AV1. Falls back to the default
         * H.264 + AAC. Flip to true to re-enable once the muxing writes a complete av1C.
         */
        private const val ENABLE_AV1_OPUS_RECORDING = false

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

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSec = _recordingDurationSec.asStateFlow()
    private var recordingTimerJob: kotlinx.coroutines.Job? = null

    private val _timerCountdown = MutableStateFlow(0)
    val timerCountdown = _timerCountdown.asStateFlow()

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

    private val _exposureCompensation = MutableStateFlow(0f)
    val exposureCompensation = _exposureCompensation.asStateFlow()

    private val _warmth = MutableStateFlow(0f)
    val warmth = _warmth.asStateFlow()

    private val _shadows = MutableStateFlow(0f)
    val shadows = _shadows.asStateFlow()

    private val _exposureTimeIndex = MutableStateFlow(0)
    val exposureTimeIndex = _exposureTimeIndex.asStateFlow()

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

    // Latest device orientation as a Surface.ROTATION_* constant. Driven by the UI's
    // OrientationEventListener and applied to ImageCapture so landscape shots save
    // with the correct orientation even while the activity stays locked to portrait.
    private var targetRotation: Int = Surface.ROTATION_0

    /** True once the photo session's use cases (incl. ImageAnalysis) are bound. */
    private val _photoSessionActive = MutableStateFlow(false)
    val photoSessionActive = _photoSessionActive.asStateFlow()

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

    fun setSloMoFps(fps: Int) {
        sloMoFps = fps
    }

    init {
        loadSettings()
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
        ds.getString("camera_location")?.let { _locationEnabled.value = it.toBoolean() }
        ds.getString("camera_last_capture")?.let { _lastCaptureUri.value = Uri.parse(it) }
        ds.getString("camera_grid")?.let { _gridEnabled.value = it.toBoolean() }
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

    fun setLocationEnabled(enabled: Boolean) {
        _locationEnabled.value = enabled
        viewModelScope.launch { ds.setString("camera_location", enabled.toString()) }
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
        viewModelScope.launch { ds.setString("camera_grid", _gridEnabled.value.toString()) }
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

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            fun bind(maxRes: Boolean): Camera {
                val selectorBuilder = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                if (maxRes) {
                    // HIGHEST_AVAILABLE + PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE unlocks the
                    // full-res (maximum-resolution) sensor sizes, re-resolved per bound camera.
                    selectorBuilder.setAllowedResolutionMode(
                        ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                    )
                }
                val capture = ImageCapture.Builder()
                    .setResolutionSelector(selectorBuilder.build())
                    .setFlashMode(getImageCaptureFlashMode())
                    .setTargetRotation(targetRotation)
                    .build()
                imageCapture = capture
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis = analysis
                return provider.bindToLifecycle(owner, selector, preview, capture, analysis)
            }

            boundCamera = try {
                bind(maxRes = true)
            } catch (e: Exception) {
                Log.w("PhotoSession", "Max-res 3-stream bind failed; falling back to default resolution", e)
                provider.unbindAll()
                bind(maxRes = false)
            }

            boundCamera?.cameraInfo?.zoomState?.value?.let {
                updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
                _zoomRatio.value = it.zoomRatio
            }
            _photoSessionActive.value = true
            true
        } catch (e: Exception) {
            Log.e("PhotoSession", "Failed to set up photo session", e)
            false
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

            val useAv1 = ENABLE_AV1_OPUS_RECORDING &&
                CodecSupport.isHardwareAv1EncoderAvailable && av1SupportedByCamera(cameraInfo)
            val useOpus = ENABLE_AV1_OPUS_RECORDING && CodecSupport.isOpusEncoderAvailable
            Log.d("VideoSession", "Codec selection: av1=$useAv1, opus=$useOpus")

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

            val owner = ManualLifecycleOwner()
            owner.start()
            sessionLifecycleOwner = owner

            fun bind(av1: Boolean, opus: Boolean): Camera {
                val recorderBuilder = Recorder.Builder()
                if (av1) recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AV1)
                if (opus) recorderBuilder.setAudioMimeType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                val capture = VideoCapture.Builder(recorderBuilder.build())
                    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                    .build()
                capture.targetRotation = targetRotation
                videoCapture = capture
                recordingWithAv1 = av1
                return provider.bindToLifecycle(owner, selector, preview, capture)
            }

            boundCamera = try {
                bind(useAv1, useOpus)
            } catch (e: Exception) {
                if (!useAv1 && !useOpus) throw e
                Log.w("VideoSession", "AV1/Opus session failed to bind; falling back to defaults", e)
                provider.unbindAll()
                bind(av1 = false, opus = false)
            }

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

    /** Tears down whatever session is currently bound and clears the shared preview surface. */
    fun teardownSession() {
        currentRecording?.stop()
        currentRecording = null
        highSpeedRecording?.stop()
        highSpeedRecording = null
        imageAnalysis?.clearAnalyzer()
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
    }

    // --- Unified manual session control wiring (targets the single bound camera) ---

    fun startFocusAndMetering(action: FocusMeteringAction) {
        boundCamera?.cameraControl?.startFocusAndMetering(action)
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
        if (analyzer == null) analysis.clearAnalyzer()
        else analysis.setAnalyzer(ContextCompat.getMainExecutor(app), analyzer)
    }

    private fun startRecordingTimer() {
        _isRecording.value = true
        _recordingDurationSec.value = 0
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _recordingDurationSec.value += 1
            }
        }
    }

    private fun stopRecordingTimer() {
        _isRecording.value = false
        recordingTimerJob?.cancel()
        _recordingDurationSec.value = 0
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun toggleHighSpeedRecording() {
        val videoCapture = highSpeedVideoCapture ?: return

        if (_isRecording.value) {
            highSpeedRecording?.stop()
            highSpeedRecording = null
            stopRecordingTimer()
            return
        }

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
        val cam2Control = try {
            boundCamera?.cameraControl?.let {
                androidx.camera.camera2.interop.Camera2CameraControl.from(it)
            }
        } catch (e: Exception) { Log.w("CameraViewModel", "Camera2 control unavailable", e); null }

        fun restoreAutoExposure() {
            if (stop.nanos != null && cam2Control != null) {
                try {
                    cam2Control.setCaptureRequestOptions(
                        androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME)
                            .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE)
                            .build()
                    )
                } catch (e: Exception) {
                    Log.w("CameraViewModel", "Failed to restore auto exposure", e)
                }
            }
        }

        fun doCapture() {
            if (stop.nanos != null && stop.nanos >= 250_000_000L) {
                startLongExposureCountdown(stop.nanos)
            }
            fun finishCapture(uri: Uri?) {
                _isCapturing.value = false
                stopLongExposureCountdown()
                restoreAutoExposure()
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

        if (stop.nanos != null && cam2Control != null) {
            try {
                cam2Control.setCaptureRequestOptions(
                    androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                        )
                        .setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                            stop.nanos
                        )
                        .build()
                ).addListener({ doCapture() }, ContextCompat.getMainExecutor(app))
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to set manual exposure", e)
                doCapture()
            }
        } else {
            doCapture()
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
     * Restores the EXIF that re-encoding the adjusted bitmap dropped: copies the original frame's
     * metadata tags, writes the orientation for [rotationDegrees], and stamps GPS when location is
     * enabled — so an adjusted photo carries the same EXIF as an unadjusted one.
     */
    private fun writeCaptureExif(uri: Uri, sourceJpeg: ByteArray, rotationDegrees: Int) {
        try {
            val source = ExifInterface(ByteArrayInputStream(sourceJpeg))
            app.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val dest = ExifInterface(pfd.fileDescriptor)
                EXIF_TAGS_TO_COPY.forEach { tag ->
                    source.getAttribute(tag)?.let { dest.setAttribute(tag, it) }
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
        if (_isRecording.value) {
            currentRecording?.stop()
            currentRecording = null
            stopRecordingTimer()
            return
        }

        val capture = videoCapture ?: return

        val timestamp = MediaStoreSaver.timestamp()
        val prefix = when (_cameraMode.value) {
            CameraMode.TIMELAPSE -> "TL"
            else -> "VID"
        }
        val contentValues = MediaStoreSaver.videoValues("${prefix}_$timestamp")

        val cacheFile = java.io.File(app.cacheDir, "VID_$timestamp.mp4")
        val outputOptions = FileOutputOptions.Builder(cacheFile).build()
        val recordingMode = _cameraMode.value

        startRecordingTimer()

        var pending = capture.output.prepareRecording(app, outputOptions)
        if (recordingMode == CameraMode.VIDEO) {
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
    }

    fun startPanorama() = panoramaEngine.startSweep()

    fun stopPanorama() = finishPanoramaSweep()

    fun startPhotosphere() = panoramaEngine.startSweep(fullSphere = true)

    fun stopPhotosphere() = finishPanoramaSweep()

    private fun finishPanoramaSweep() {
        panoramaEngine.stopSweep()
        viewModelScope.launch {
            panoramaEngine.stitch()?.let {
                panoramaEngine.saveToMediaStore(it)
                it.recycle()
            }
            panoramaEngine.reset()
        }
    }

    fun getImageCaptureFlashMode(): Int = when (_flashMode.value) {
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
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
