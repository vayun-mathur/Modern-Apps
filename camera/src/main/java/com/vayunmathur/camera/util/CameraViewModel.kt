package com.vayunmathur.camera.util

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.video.AudioConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.camera.lifecycle.awaitInstance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class CameraMode { PHOTO, PORTRAIT, PANORAMA, PHOTOSPHERE, VIDEO, SLOW_MO, TIMELAPSE }
enum class FlashMode { ON, OFF, AUTO }
enum class TimerDuration(val seconds: Int) { NONE(0), THREE(3), FIVE(5), TEN(10) }
enum class AspectRatioOption(val label: String) { RATIO_16_9("16:9"), RATIO_4_3("4:3"), RATIO_1_1("1:1") }

class CameraViewModel(private val app: Application) : AndroidViewModel(app) {
    private val ds = DataStoreUtils.getInstance(app)

    private val _cameraMode = MutableStateFlow(CameraMode.PHOTO)
    val cameraMode = _cameraMode.asStateFlow()

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing = _lensFacing.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode = _flashMode.asStateFlow()

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

    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled = _gridEnabled.asStateFlow()

    private val _exposureCompensation = MutableStateFlow(0f)
    val exposureCompensation = _exposureCompensation.asStateFlow()

    private val _warmth = MutableStateFlow(0f)
    val warmth = _warmth.asStateFlow()

    private val _shadows = MutableStateFlow(0f)
    val shadows = _shadows.asStateFlow()

    private var lastLocation: Location? = null
    private var currentRecording: Recording? = null
    private var sloMoFps: Int = 30

    val panoramaEngine = PanoramaEngine(app)

    // High-speed session state
    private var cameraProvider: ProcessCameraProvider? = null
    private var highSpeedVideoCapture: VideoCapture<Recorder>? = null
    private var highSpeedRecording: Recording? = null
    private var highSpeedCamera: androidx.camera.core.Camera? = null
    private var highSpeedLifecycleOwner: HighSpeedLifecycleOwner? = null

    private val _highSpeedActive = MutableStateFlow(false)
    val highSpeedActive = _highSpeedActive.asStateFlow()

    fun setSloMoFps(fps: Int) {
        sloMoFps = fps
    }

    init {
        loadSettings()
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

    private fun setLastCaptureUri(uri: Uri?) {
        _lastCaptureUri.value = uri
        viewModelScope.launch { ds.setString("camera_last_capture", uri?.toString() ?: "") }
    }

    fun setCameraMode(mode: CameraMode) {
        _cameraMode.value = mode
    }

    fun switchCameraMode(newMode: CameraMode) {
        _cameraMode.value = newMode
    }

    fun flipCamera() {
        _lensFacing.value = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
    }

    fun setQrResult(text: String?) {
        _qrResult.value = text
    }

    fun setZoomRatio(ratio: Float) {
        _zoomRatio.value = ratio
        highSpeedCamera?.cameraControl?.setZoomRatio(ratio)
    }

    suspend fun updateZoomLevels(lensFacing: Int) {
        _availableZoomLevels.value = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            listOf(".5" to 0.5f, "1x" to 1f, "2x" to 2f, "5x" to 5f)
        } else {
            listOf(".7" to 0.7f, "1x" to 1f)
        }
    }

    private fun formatZoomLabel(ratio: Float): String {
        val tenths = (ratio * 10f).roundToInt()
        return when {
            tenths < 10 -> ".${tenths}"
            tenths == 10 -> "1x"
            tenths % 10 == 0 -> "${tenths / 10}"
            else -> "${tenths / 10}.${tenths % 10}"
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun updateLocation() {
        if (!_locationEnabled.value) return
        try {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lastLocation = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { }
    }

    // --- High-speed session management ---

    suspend fun setupHighSpeedSession(
        surfaceProvider: Preview.SurfaceProvider
    ): Boolean {
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
            preview.surfaceProvider = surfaceProvider

            val recorder = Recorder.Builder().build()
            val videoCapture = VideoCapture.withOutput(recorder)
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

            val hsOwner = HighSpeedLifecycleOwner()
            hsOwner.start()
            highSpeedLifecycleOwner = hsOwner
            highSpeedCamera = provider.bindToLifecycle(hsOwner, selector, configBuilder.build())

            // Set anti-banding to reduce flicker under artificial light
            try {
                val cam2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(
                    highSpeedCamera!!.cameraControl
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

            highSpeedCamera?.cameraInfo?.let { updateZoomLevels(_lensFacing.value) }
            _highSpeedActive.value = true
            true
        } catch (e: Exception) {
            Log.e("SloMo", "Failed to set up high-speed session", e)
            false
        }
    }

    fun teardownHighSpeedSession() {
        highSpeedRecording?.stop()
        highSpeedRecording = null
        highSpeedLifecycleOwner?.destroy()
        highSpeedLifecycleOwner = null
        cameraProvider?.unbindAll()
        highSpeedVideoCapture = null
        highSpeedCamera = null
        cameraProvider = null
        _highSpeedActive.value = false
    }

    fun clearHighSpeedState() {
        highSpeedRecording?.stop()
        highSpeedRecording = null
        highSpeedLifecycleOwner?.destroy()
        highSpeedLifecycleOwner = null
        cameraProvider?.unbindAll()
        highSpeedVideoCapture = null
        highSpeedCamera = null
        cameraProvider = null
        _highSpeedActive.value = false
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun toggleHighSpeedRecording() {
        val videoCapture = highSpeedVideoCapture ?: return

        if (_isRecording.value) {
            highSpeedRecording?.stop()
            highSpeedRecording = null
            _isRecording.value = false
            recordingTimerJob?.cancel()
            _recordingDurationSec.value = 0
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SLOMO_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            app.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        _isRecording.value = true
        _recordingDurationSec.value = 0
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _recordingDurationSec.value += 1
            }
        }

        Log.d("SloMo", "Starting high-speed recording at ${sloMoFps}fps")

        highSpeedRecording = videoCapture.output
            .prepareRecording(app, outputOptions)
            .start(ContextCompat.getMainExecutor(app)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    _isRecording.value = false
                    recordingTimerJob?.cancel()
                    _recordingDurationSec.value = 0
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

    fun takePhoto(controller: LifecycleCameraController) {
        val timer = _timerDuration.value
        if (timer.seconds > 0) {
            viewModelScope.launch {
                for (i in timer.seconds downTo 1) {
                    _timerCountdown.value = i
                    delay(1000)
                }
                _timerCountdown.value = 0
                capturePhoto(controller)
            }
        } else {
            capturePhoto(controller)
        }
    }

    private fun capturePhoto(controller: LifecycleCameraController) {
        _isCapturing.value = true
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timestamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }

        val metadata = ImageCapture.Metadata().apply {
            if (_locationEnabled.value && lastLocation != null) {
                location = lastLocation
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            app.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).setMetadata(metadata).build()

        controller.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    _isCapturing.value = false
                    setLastCaptureUri(outputFileResults.savedUri)
                }
                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                }
            }
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun toggleRecording(controller: LifecycleCameraController) {
        if (_isRecording.value) {
            currentRecording?.stop()
            currentRecording = null
            _isRecording.value = false
            recordingTimerJob?.cancel()
            _recordingDurationSec.value = 0
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = when (_cameraMode.value) {
            CameraMode.SLOW_MO -> "SLOMO"
            CameraMode.TIMELAPSE -> "TL"
            else -> "VID"
        }
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }

        val mediaStoreOutput = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cacheFile = java.io.File(app.cacheDir, "VID_$timestamp.mp4")
        val outputOptions = FileOutputOptions.Builder(cacheFile).build()
        val recordingMode = _cameraMode.value

        _isRecording.value = true
        _recordingDurationSec.value = 0
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _recordingDurationSec.value += 1
            }
        }
        currentRecording = controller.startRecording(
            outputOptions,
            AudioConfig.create(recordingMode == CameraMode.VIDEO),
            ContextCompat.getMainExecutor(app)
        ) { event ->
            if (event is VideoRecordEvent.Finalize) {
                _isRecording.value = false
                recordingTimerJob?.cancel()
                _recordingDurationSec.value = 0
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (!cacheFile.exists()) return@launch
                    val fileToSave = when (recordingMode) {
                        CameraMode.SLOW_MO -> {
                            val processed = java.io.File(app.cacheDir, "SLOMO_$timestamp.mp4")
                            val actualFps = try {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(cacheFile.path)
                                val frameCount = retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
                                )?.toLongOrNull() ?: 0L
                                val durationMs = retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION
                                )?.toLongOrNull() ?: 0L
                                retriever.release()
                                val detected = if (durationMs > 0) (frameCount * 1000f / durationMs) else sloMoFps.toFloat()
                                Log.d("SloMo", "Fallback: ${frameCount} frames, ${durationMs}ms, detected=${detected}fps")
                                detected
                            } catch (e: Exception) {
                                Log.e("SloMo", "Failed to detect FPS", e)
                                sloMoFps.toFloat()
                            }
                            val effectiveFps = maxOf(actualFps, sloMoFps.toFloat())
                            val speedFactor = 30f / effectiveFps
                            Log.d("SloMo", "Fallback slowing: effectiveFps=$effectiveFps, speedFactor=$speedFactor")
                            VideoProcessor.adjustSpeed(cacheFile, processed, speedFactor)
                            cacheFile.delete()
                            processed
                        }
                        CameraMode.TIMELAPSE -> {
                            val processed = java.io.File(app.cacheDir, "TL_$timestamp.mp4")
                            VideoProcessor.adjustSpeed(cacheFile, processed, 8f)
                            cacheFile.delete()
                            processed
                        }
                        else -> cacheFile
                    }
                    val uri = app.contentResolver.insert(mediaStoreOutput, contentValues)
                    uri?.let {
                        app.contentResolver.openOutputStream(it)?.use { os ->
                            fileToSave.inputStream().use { input -> input.copyTo(os) }
                        }
                        setLastCaptureUri(it)
                    }
                    fileToSave.delete()
                }
            }
        }
    }

    fun startPanorama() {
        panoramaEngine.startSweep()
    }

    fun stopPanorama() {
        panoramaEngine.stopSweep()
        viewModelScope.launch {
            val result = panoramaEngine.stitch()
            if (result != null) {
                panoramaEngine.saveToMediaStore(result)
                result.recycle()
            }
            panoramaEngine.reset()
        }
    }

    fun startPhotosphere() {
        panoramaEngine.startSweep(fullSphere = true)
    }

    fun stopPhotosphere() {
        panoramaEngine.stopSweep()
        viewModelScope.launch {
            val result = panoramaEngine.stitch()
            if (result != null) {
                panoramaEngine.saveToMediaStore(result)
                result.recycle()
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

private class HighSpeedLifecycleOwner : LifecycleOwner {
    private val registry = androidx.lifecycle.LifecycleRegistry(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry

    fun start() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
    }
}
