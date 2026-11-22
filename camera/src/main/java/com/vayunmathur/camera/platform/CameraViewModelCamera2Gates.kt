package com.vayunmathur.camera.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.launch

/**
 * Camera2 engine gate actions for [CameraViewModel]: preview fallback
 * handling and the persisted on/off setters for the preview, slo-mo,
 * night and RAW-night Camera2 routes. The gate state flows themselves
 * stay on the ViewModel; these extensions only mutate them, exactly as
 * the former member functions did.
 */

/**
 * Records a Camera2 preview configure/bind failure. First failure for
 * this bind key latches (returns true) and the UI switches that
 * session to CameraX; repeat failures for the same key are ignored.
 */
fun CameraViewModel.onCamera2PreviewFailed(cameraId: String): Boolean {
    val key = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch
        .bindKey(cameraId, cameraModeMutable.value)
    if (!camera2FallbackLatch.recordFailure(key)) return false
    camera2FallbackBindKeyMutable.value = key
    Log.status("Camera2Preview", "Camera2 bind failed for $key; falling back to CameraX for this session")
    return true
}

/** Clears the fallback latch (manual retry / gate re-enable / new bind). */
fun CameraViewModel.clearCamera2Fallback() {
    camera2FallbackLatch.reset()
    camera2FallbackBindKeyMutable.value = null
}

/** Turns the Camera2 engine on/off (PHOTO only; see PreviewEnginePolicy). */
fun CameraViewModel.setCamera2PreviewEnabled(enabled: Boolean) {
    camera2PreviewEnabledMutable.value = enabled
    if (enabled) clearCamera2Fallback()
    viewModelScope.launch { ds.setString("camera_camera2_engine", enabled.toString()) }
}

/** Turns the Camera2 slo-mo gate on/off (SLOW_MO only; see SloMoEnginePolicy). */
fun CameraViewModel.setCamera2SloMoEnabled(enabled: Boolean) {
    camera2SloMoEnabledMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_camera2_slomo", enabled.toString()) }
}

/** Turns the Camera2 night gate on/off (PHOTO only; see ExtensionsEnginePolicy). */
fun CameraViewModel.setCamera2NightEnabled(enabled: Boolean) {
    camera2NightEnabledMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_camera2_night", enabled.toString()) }
}

/** Turns the RAW-night Camera2 route on/off (see RawNightPolicy). */
fun CameraViewModel.setCamera2RawNightEnabled(enabled: Boolean) {
    camera2RawNightEnabledMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_camera2_raw_night", enabled.toString()) }
}
