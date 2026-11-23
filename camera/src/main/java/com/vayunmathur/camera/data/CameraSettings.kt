package com.vayunmathur.camera.data

import android.content.Context
import android.location.LocationManager
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.CodecSupport
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.MotionPhotoDuration
import com.vayunmathur.camera.platform.PhotoFormat
import com.vayunmathur.camera.platform.TimerDuration
import com.vayunmathur.camera.platform.VideoCodec
import com.vayunmathur.camera.platform.applyManualControls
import com.vayunmathur.camera.platform.clearMotionFrames
import com.vayunmathur.camera.platform.currentCropAspectRatio
import com.vayunmathur.camera.platform.registerLevelSensor
import com.vayunmathur.camera.platform.unregisterLevelSensor

internal fun CameraViewModel.loadSettings() {
    loadCaptureSettings()
    loadPreferenceToggles()
    if (levelEnabledMutable.value) registerLevelSensor()
}

/** Flash/timer/aspect/codec/audio-device/save-target/last-capture restores. */
private fun CameraViewModel.loadCaptureSettings() {
    ds.getString("camera_flash")?.let {
        runCatching { flashModeMutable.value = FlashMode.valueOf(it) }
    }
    ds.getString("camera_timer")?.let {
        runCatching { timerDurationMutable.value = TimerDuration.valueOf(it) }
    }
    ds.getString("camera_aspect_ratio")?.let {
        runCatching { aspectRatioMutable.value = AspectRatioOption.valueOf(it) }
    }
    ds.getString("camera_video_codec")?.let {
        videoCodecMutable.value = try { VideoCodec.valueOf(it) } catch (_: Exception) {
            CodecSupport.defaultVideoCodec
        }
    }
    ds.getString("camera_photo_format")?.let {
        runCatching { photoFormatMutable.value = PhotoFormat.valueOf(it) }
    }
    ds.getString("camera_motion_duration")?.let {
        runCatching { motionPhotoDurationMutable.value = MotionPhotoDuration.valueOf(it) }
    }
    // Physical input device (id+type), not an AudioSource type. A stale
    // or malformed stored value parses to Auto, never crashes.
    ds.getString("camera_audio_input_device")?.let {
        audioInputDeviceMutable.value = AudioInputDeviceSelection.parse(it)
    }
    // Retired settings are never read again: the AudioSource-type choice
    // (camera_audio_source, e.g. a stale "CAMCORDER"/"MIC" value) and the
    // audio-codec choice (camera_audio_codec; Opus-only). Drop any stored
    // values so they cannot linger or be misread later; they are simply
    // deleted, never parsed.
    viewModelScope.launch { ds.removeKeys(listOf("camera_audio_codec", "camera_audio_source")) }
    loadSaveTarget()
    // Guard against a blank persisted value: Uri.parse("") yields a non-null
    ds.getString("camera_last_capture")?.takeIf { it.isNotBlank() }?.let { lastCaptureUriMutable.value = it.toUri() }
}

/** Boolean/float preference restores (location, grid, level, mic, zoom, mirror, NR/edge). */
private fun CameraViewModel.loadPreferenceToggles() {
    ds.getString("camera_location")?.let { locationEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_grid")?.let { gridEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_level")?.let { levelEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_mic_muted")?.let { micMutedMutable.value = it.toBoolean() }
    ds.getString("camera_zoom_ratio")?.toFloatOrNull()?.let { zoomRatioMutable.value = it }
    ds.getString("camera_mirror_front")?.let { mirrorFrontMutable.value = it.toBoolean() }
    ds.getString("camera_noise_reduction")?.let { noiseReductionEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_edge_sharpening")?.let { edgeSharpeningEnabledMutable.value = it.toBoolean() }
    // Absent key keeps the ViewModel default (on), so existing users see no change.
    ds.getString("camera_motion_photo_enabled")?.let { motionPhotoEnabledMutable.value = it.toBoolean() }
}

fun CameraViewModel.setFlashMode(mode: FlashMode) {
    flashModeMutable.value = mode
    viewModelScope.launch { ds.setString("camera_flash", mode.name) }
}

/**
 * Toggle horizontal mirroring of the front-camera preview and saved photo/video (issue #632).
 * Takes effect on the next capture; the preview updates immediately via the UI layer.
 * Mid-recording the value persists but the bound session keeps the old MirrorMode
 * (bindTimeSettings) until the recording stops — it never rebinds under a take.
 */
fun CameraViewModel.setMirrorFront(enabled: Boolean) {
    mirrorFrontMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_mirror_front", enabled.toString()) }
}

/**
 * Noise-reduction toggle (issue #662). Takes effect live via the capture path and
 * persists for the next session.
 */
fun CameraViewModel.setNoiseReductionEnabled(enabled: Boolean) {
    noiseReductionEnabledMutable.value = enabled
    applyManualControls()
    viewModelScope.launch { ds.setString("camera_noise_reduction", enabled.toString()) }
}

/**
 * Edge-sharpening toggle (issue #662). Takes effect live via the capture path and
 * persists for the next session.
 */
fun CameraViewModel.setEdgeSharpeningEnabled(enabled: Boolean) {
    edgeSharpeningEnabledMutable.value = enabled
    applyManualControls()
    viewModelScope.launch { ds.setString("camera_edge_sharpening", enabled.toString()) }
}

fun CameraViewModel.toggleTorch() {
    torchEnabledMutable.value = !torchEnabledMutable.value
}

fun CameraViewModel.setTimerDuration(duration: TimerDuration) {
    timerDurationMutable.value = duration
    viewModelScope.launch { ds.setString("camera_timer", duration.name) }
}

fun CameraViewModel.setAspectRatio(ratio: AspectRatioOption) {
    aspectRatioMutable.value = ratio
    // The Camera2 still size/crop follows this flow at capture time.
    viewModelScope.launch { ds.setString("camera_aspect_ratio", ratio.name) }
}

/** Cycles the aspect ratio 4:3 → 3:2 → 16:9 → 1:1 → 4:3 (top-bar icon). */
fun CameraViewModel.cycleAspectRatio() {
    val order = listOf(
        AspectRatioOption.RATIO_4_3,
        AspectRatioOption.RATIO_3_2,
        AspectRatioOption.RATIO_16_9,
        AspectRatioOption.RATIO_1_1
    )
    val next = order[(order.indexOf(aspectRatioMutable.value) + 1) % order.size]
    setAspectRatio(next)
}

fun CameraViewModel.setLocationEnabled(enabled: Boolean) {
    locationEnabledMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_location", enabled.toString()) }
}

fun CameraViewModel.setPhotoFormat(format: PhotoFormat) {
    photoFormatMutable.value = format
    viewModelScope.launch { ds.setString("camera_photo_format", format.name) }
}

fun CameraViewModel.setVideoCodec(codec: VideoCodec) {
    videoCodecMutable.value = codec
    viewModelScope.launch { ds.setString("camera_video_codec", codec.name) }
}

/**
 * Motion Photo on/off. Off takes plain stills always: no capture diversion
 * and no ring-buffer accumulation, whatever the length/ratio/format. Drops
 * any frames buffered while it was on so a later re-enable cannot attach a
 * clip from before the off period.
 */
fun CameraViewModel.setMotionPhotoEnabled(enabled: Boolean) {
    motionPhotoEnabledMutable.value = enabled
    if (!enabled) clearMotionFrames()
    viewModelScope.launch { ds.setString("camera_motion_photo_enabled", enabled.toString()) }
}

/** Motion-Photo clip length (issue #712); applies to the next capture's ring buffer. */
fun CameraViewModel.setMotionPhotoDuration(duration: MotionPhotoDuration) {
    motionPhotoDurationMutable.value = duration
    viewModelScope.launch { ds.setString("camera_motion_duration", duration.name) }
}

fun CameraViewModel.setAudioInputDevice(selection: AudioInputDeviceSelection) {
    audioInputDeviceMutable.value = selection
    viewModelScope.launch { ds.setString("camera_audio_input_device", selection.encode()) }
}

fun CameraViewModel.toggleGrid() {
    gridEnabledMutable.value = !gridEnabledMutable.value
    viewModelScope.launch { ds.setString("camera_grid", gridEnabledMutable.value.toString()) }
}

fun CameraViewModel.toggleLevel() {
    levelEnabledMutable.value = !levelEnabledMutable.value
    if (levelEnabledMutable.value) registerLevelSensor() else unregisterLevelSensor()
    viewModelScope.launch { ds.setString("camera_level", levelEnabledMutable.value.toString()) }
}

/** Maps the 0..1 blur-strength UI value to the bokeh shader's blurScale multiplier. */
fun CameraViewModel.setBlurStrength(value: Float) {
    blurStrengthMutable.value = value.coerceIn(0f, 1f)
}

fun CameraViewModel.setExposureCompensation(value: Float) {
    exposureCompensationMutable.value = value
}

fun CameraViewModel.setWarmth(value: Float) {
    warmthMutable.value = value
}

fun CameraViewModel.setShadows(value: Float) {
    shadowsMutable.value = value
}

internal fun CameraViewModel.setLastCaptureUri(uri: Uri?) {
    lastCaptureUriMutable.value = uri
    viewModelScope.launch { ds.setString("camera_last_capture", uri?.toString() ?: "") }
}

@android.annotation.SuppressLint("MissingPermission")
fun CameraViewModel.updateLocation() {
    if (!locationEnabledMutable.value) return
    try {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lastLocation = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    } catch (e: SecurityException) {
        Log.status("CameraViewModel", "Failed to read last known location", e)
    }
}
