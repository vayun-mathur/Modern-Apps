package com.vayunmathur.camera.util

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.video.AudioConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CameraMode { PHOTO, PORTRAIT, PANORAMA, VIDEO, SLOW_MO, TIMELAPSE }
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

    private val _timerCountdown = MutableStateFlow(0)
    val timerCountdown = _timerCountdown.asStateFlow()

    private val _qrResult = MutableStateFlow<String?>(null)
    val qrResult = _qrResult.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio = _zoomRatio.asStateFlow()

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

    val panoramaEngine = PanoramaEngine(app)

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

    fun flipCamera() {
        _lensFacing.value = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
    }

    fun setQrResult(text: String?) {
        _qrResult.value = text
    }

    fun setZoomRatio(ratio: Float) {
        _zoomRatio.value = ratio
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
            androidx.core.content.ContextCompat.getMainExecutor(app),
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
        currentRecording = controller.startRecording(
            outputOptions,
            AudioConfig.create(recordingMode == CameraMode.VIDEO),
            androidx.core.content.ContextCompat.getMainExecutor(app)
        ) { event ->
            if (event is VideoRecordEvent.Finalize) {
                _isRecording.value = false
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (!cacheFile.exists()) return@launch
                    val fileToSave = when (recordingMode) {
                        CameraMode.SLOW_MO -> {
                            val processed = java.io.File(app.cacheDir, "SLOMO_$timestamp.mp4")
                            VideoProcessor.adjustSpeed(cacheFile, processed, 0.25f)
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

    fun getImageCaptureFlashMode(): Int = when (_flashMode.value) {
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    }
}
