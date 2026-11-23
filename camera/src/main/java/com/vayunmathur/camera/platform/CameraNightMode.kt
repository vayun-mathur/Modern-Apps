package com.vayunmathur.camera.platform

import android.hardware.camera2.CameraManager
import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.platform.camera2.Camera2ExtensionProbe
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import kotlinx.coroutines.launch

internal fun CameraViewModel.nightExtFailedRecently(): Boolean {
    val at = ds.getString("night_ext_failed_at")?.toLongOrNull() ?: return false
    return System.currentTimeMillis() - at < CameraViewModel.NIGHT_EXT_FAILURE_TTL_MS
}

internal fun CameraViewModel.recordNightExtensionFailure() {
    viewModelScope.launch { ds.setString("night_ext_failed_at", System.currentTimeMillis().toString()) }
    nightExtensionUsableMutable.value = false
}

/**
 * Re-evaluates whether night should be offered on the current lens/mode. Cheap checks first
 * (mode + the daily failure cache), then the isSessionConfigSupported() probe against the
 * selected lens' selector. Heavy — call off the main thread.
 */
suspend fun CameraViewModel.refreshNightExtensionUsable(cameraMode: CameraMode) {
    nightExtensionUsableMutable.value = when {
        cameraMode != CameraMode.PHOTO -> false
        nightExtFailedRecently() -> false
        else -> isNightExtensionAvailable()
    }
}

/**
 * Feeds the PhotoAnalyzer's average scene luminance through a hysteresis + debounce filter so
 * night mode engages/disengages smoothly. When the scene brightens back up (true→false), the
 * user's per-scene override is reset so the next dark scene re-engages cleanly.
 */
fun CameraViewModel.onLuminance(avg: Float) {
    // Night engagement is this luma hysteresis (there is no Camera2
    // night-mode indicator equivalent; see Camera2ExtensionProbe's
    // detection contract).
    val beforeLow = lowLightDetectedMutable.value
    val beforeOff = nightModeOverriddenOffMutable.value
    Log.dev(
        "NightPreview",
        "onLuminance() avg=$avg lowLightBefore=$beforeLow overriddenOff=$beforeOff " +
            "lowFrames=$lowLumaFrames highFrames=$highLumaFrames " +
            "nightActive=${nightModeActive.value} photoActive=${photoSessionActiveMutable.value} " +
            "nightPreviewActive=${nightPreviewActiveMutable.value} thread=${Thread.currentThread().name}"
    )
    if (lowLightDetectedMutable.value) {
        trackBrightening(avg)
    } else {
        trackDarkening(avg)
    }
    if (beforeLow != lowLightDetectedMutable.value || beforeOff != nightModeOverriddenOffMutable.value) {
        Log.dev(
            "NightPreview",
            "onLuminance() STATE CHANGE low $beforeLow -> ${lowLightDetectedMutable.value} " +
                "off $beforeOff -> ${nightModeOverriddenOffMutable.value} nightActive ${nightModeActive.value}"
        )
    }
}

/** Bright-scene arm of the hysteresis filter: disengages night after enough bright frames. */
private fun CameraViewModel.trackBrightening(avg: Float) {
    if (avg <= CameraViewModel.NIGHT_DISENGAGE_LUMA) {
        if (highLumaFrames != 0) {
            Log.dev(
                "NightPreview",
                "onLuminance() resetting highFrames 0 (avg=$avg still below disengage)"
            )
        }
        highLumaFrames = 0
        return
    }
    highLumaFrames++
    lowLumaFrames = 0
    Log.dev(
        "NightPreview",
        "onLuminance() currently in low-light, avg $avg > disengage " +
            "${CameraViewModel.NIGHT_DISENGAGE_LUMA}, " +
            "highFrames=$highLumaFrames/${CameraViewModel.NIGHT_DEBOUNCE_FRAMES}"
    )
    if (highLumaFrames >= CameraViewModel.NIGHT_DEBOUNCE_FRAMES) {
        Log.dev(
            "NightPreview",
            "onLuminance() DISENGAGING night – high luma for " +
                "${CameraViewModel.NIGHT_DEBOUNCE_FRAMES} frames, lowLight=true->false"
        )
        lowLightDetectedMutable.value = false
        nightModeOverriddenOffMutable.value = false
        highLumaFrames = 0
        Log.dev(
            "NightPreview",
            "onLuminance() after DISENGAGE lowLight=${lowLightDetectedMutable.value} " +
                "nightActive=${nightModeActive.value} – triggers a session rebind"
        )
    }
}

/** Dark-scene arm of the hysteresis filter: engages night after enough dark frames. */
private fun CameraViewModel.trackDarkening(avg: Float) {
    if (avg >= CameraViewModel.NIGHT_ENGAGE_LUMA) {
        if (lowLumaFrames != 0) {
            Log.dev("NightPreview", "onLuminance() resetting lowFrames 0 (avg=$avg above engage)")
        }
        lowLumaFrames = 0
        return
    }
    lowLumaFrames++
    highLumaFrames = 0
    Log.dev(
        "NightPreview",
        "onLuminance() avg $avg < engage ${CameraViewModel.NIGHT_ENGAGE_LUMA}, " +
            "lowFrames=$lowLumaFrames/${CameraViewModel.NIGHT_DEBOUNCE_FRAMES}"
    )
    if (lowLumaFrames >= CameraViewModel.NIGHT_DEBOUNCE_FRAMES) {
        Log.dev(
            "NightPreview",
            "onLuminance() ENGAGING night – low luma for " +
                "${CameraViewModel.NIGHT_DEBOUNCE_FRAMES} frames, lowLight=false->true"
        )
        lowLightDetectedMutable.value = true
        lowLumaFrames = 0
        Log.dev(
            "NightPreview",
            "onLuminance() after ENGAGE lowLight=${lowLightDetectedMutable.value} " +
                "nightActive=${nightModeActive.value} " +
                "overriddenOff=${nightModeOverriddenOffMutable.value} – if extension available, " +
                "useNightPreview should become true and rebind to setupNightPreviewSession()"
        )
    }
}

/** Toggles the user's override for the current dark scene (moon button handler). */
fun CameraViewModel.toggleNightModeOverride() {
    val before = nightModeOverriddenOffMutable.value
    nightModeOverriddenOffMutable.value = !nightModeOverriddenOffMutable.value
    Log.dev(
        "NightPreview",
        "toggleNightModeOverride() CLICK moon button beforeOff=$before " +
            "afterOff=${nightModeOverriddenOffMutable.value} lowLight=${lowLightDetectedMutable.value} " +
            "nightActiveBefore=${!before && lowLightDetectedMutable.value} " +
            "nightActiveAfter=${nightModeActive.value} thread=${Thread.currentThread().name} – " +
            "screen should transition? if extension binds, preview resolution changes " +
            "and zoom bar may collapse to 1x"
    )
}

internal fun CameraViewModel.resetNightModeDetection() {
    lowLightDetectedMutable.value = false
    nightModeOverriddenOffMutable.value = false
    lowLumaFrames = 0
    highLumaFrames = 0
}

/**
 * Whether the vendor NIGHT extension is usable on the selected lens,
 * probed from Camera2 characteristics (extension mode list + advertised
 * preview/JPEG sizes). Replaces the CameraX ExtensionsManager probe.
 */
internal fun CameraViewModel.isNightExtensionAvailable(): Boolean {
    val manager = app.getSystemService(CameraManager::class.java) ?: return false
    val facing = selectedLensMutable.value?.facing ?: currentLensFacing()
    val lensId = CharacteristicsFacade.resolveCamera2Id(manager, facing) ?: return false
    val facade = CharacteristicsFacade.tryFromCameraManager(manager, lensId) ?: return false
    return Camera2ExtensionProbe.probeNight(facade.snapshot).usable
}
