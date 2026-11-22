package com.vayunmathur.camera.platform

import android.hardware.Sensor
import android.hardware.SensorManager
import com.vayunmathur.library.log.Log
import android.util.Rational
import androidx.camera.core.CameraSelector
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** Cancels an armed photo/video shutter timer (second tap dismisses it). */
fun CameraViewModel.cancelTimerCountdown() {
    timerCountdownJob?.cancel()
    timerCountdownJob = null
    timerCountdownMutable.value = 0
}

/**
 * Still-capture crop aspect ratio, expressed width:height in the portrait UI
 * orientation (the activity is portrait-locked) so it matches the on-screen
 * preview box. CameraX crops OutputFileOptions saves to this and sets the
 * ImageProxy cropRect for in-memory captures.
 */
internal fun CameraViewModel.currentCropAspectRatio(): android.util.Rational = when (aspectRatioMutable.value) {
    AspectRatioOption.RATIO_1_1 -> Rational(CameraViewModel.ASPECT_SQUARE_W, CameraViewModel.ASPECT_SQUARE_H)
    AspectRatioOption.RATIO_4_3 -> Rational(CameraViewModel.ASPECT_4_3_W, CameraViewModel.ASPECT_4_3_H)
    AspectRatioOption.RATIO_3_2 -> Rational(CameraViewModel.ASPECT_3_2_W, CameraViewModel.ASPECT_3_2_H)
    AspectRatioOption.RATIO_16_9 -> Rational(CameraViewModel.ASPECT_16_9_W, CameraViewModel.ASPECT_16_9_H)
}

internal fun CameraViewModel.registerLevelSensor() {
    if (levelSensorRegistered) return
    val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    sensorManager.registerListener(levelListener, sensor, SensorManager.SENSOR_DELAY_UI)
    levelSensorRegistered = true
}

internal fun CameraViewModel.unregisterLevelSensor() {
    if (!levelSensorRegistered) return
    sensorManager.unregisterListener(levelListener)
    levelSensorRegistered = false
}

/**
 * Whether captures should be horizontally mirrored to match the preview. CameraX mirrors the
 * front-camera preview but saves un-mirrored by default, so selfies otherwise come out flipped.
 * Controlled by the user-facing "mirror selfie" setting (issue #632); only ever applies to the
 * front lens.
 */
internal val CameraViewModel.mirrorCaptures: Boolean
    get() = lensFacingMutable.value == CameraSelector.LENS_FACING_FRONT && mirrorFrontMutable.value

/**
 * Debug flow logging for the night-preview resolution question: whether the extension
 * uses default resolution vs max-res.
 */
internal fun CameraViewModel.startDebugLogging() {
    viewModelScope.launch {
        var last: List<Pair<String, Float>> = emptyList()
        availableZoomLevels.collect { levels ->
            if (levels != last) {
                Log.dev(
                    "NightPreview",
                    "CameraViewModel availableZoomLevels FLOW emitted=$levels previous=$last " +
                        "nightPreviewActive=${nightPreviewActiveMutable.value} " +
                        "photoActive=${photoSessionActiveMutable.value} zoomRatio=${zoomRatioMutable.value} – " +
                        "if only [1x], zoom bar appears disappeared"
                )
                last = levels
            }
        }
    }
    viewModelScope.launch {
        var lastRes: android.util.Size? = null
        surfaceRequest.collect { req ->
            val res = req?.resolution
            if (res != lastRes) {
                Log.dev(
                    "NightPreview",
                    "CameraViewModel surfaceRequest FLOW emitted res=$res previous=$lastRes " +
                        "nightPreviewActive=${nightPreviewActiveMutable.value} " +
                        "photoActive=${photoSessionActiveMutable.value} – " +
                        "null->val during extension bind, black if stuck null"
                )
                lastRes = res
            }
        }
    }
    viewModelScope.launch {
        nightModeActive.collect { active ->
            Log.dev(
                "NightPreview",
                "CameraViewModel nightModeActive FLOW=$active " +
                    "lowLight=${lowLightDetectedMutable.value} " +
                    "overriddenOff=${nightModeOverriddenOffMutable.value} – " +
                    "button toggle drives this, triggers session rebind via useNightPreview in UI"
            )
        }
    }
}

/** Fires a hardware-shutter event for the UI to run as a capture. */
fun CameraViewModel.triggerShutter() {
    shutterEventsMutable.tryEmit(Unit)
}

/** Records the requested slo-mo frame rate for the next session bind. */
fun CameraViewModel.setSloMoFps(fps: Int) {
    sloMoFps = fps
}
