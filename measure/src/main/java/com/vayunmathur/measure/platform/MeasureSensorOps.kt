package com.vayunmathur.measure.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.data.model.distanceTo
import com.vayunmathur.measure.domain.polygonArea
import com.vayunmathur.measure.domain.polygonPerimeter
import kotlinx.coroutines.launch

// ------------------------------------------------------------------
// Preferences + sensor collectors + lifecycle — moved from
// MeasureViewModel.kt (TooManyFunctions split); behavior identical.
// ------------------------------------------------------------------

internal data class PreferenceValues(
    val system: UnitSystem,
    val fractional: Boolean,
    val trueNorth: Boolean,
    val diagnostics: Boolean,
    val haptics: Boolean,
    val keepOn: Boolean,
    val levelPitch: Double?,
    val levelRoll: Double?,
    val levelEdge: Double?,
)

internal fun MeasureViewModel.loadPreferenceValuesImpl(): PreferenceValues {
    val imperial = ds.getBoolean(KEY_IMPERIAL, false)
    return PreferenceValues(
        system = if (imperial) UnitSystem.Imperial else UnitSystem.Metric,
        fractional = ds.getBoolean(KEY_FRACTIONAL, true),
        trueNorth = ds.getBoolean(KEY_TRUE_NORTH, true),
        diagnostics = ds.getBoolean(KEY_DIAGNOSTICS, false),
        haptics = ds.getBoolean(KEY_HAPTICS, true),
        keepOn = ds.getBoolean(KEY_KEEP_SCREEN_ON, true),
        // Guard against NaN sentinels written by older versions on
        // calibration clear: NaN is a stored value, not an absence.
        levelPitch = ds.getDouble(KEY_LEVEL_PITCH)?.takeUnless { it.isNaN() },
        levelRoll = ds.getDouble(KEY_LEVEL_ROLL)?.takeUnless { it.isNaN() },
        levelEdge = ds.getDouble(KEY_LEVEL_EDGE)?.takeUnless { it.isNaN() },
    )
}

internal fun MeasureViewModel.applyPreferencesImpl(p: PreferenceValues) {
    if (p.levelPitch != null && p.levelRoll != null) {
        tiltSensor.setCalibration(p.levelPitch, p.levelRoll, p.levelEdge ?: 0.0)
    }

    settingsState.value = SettingsUiState(
        unitSystem = p.system,
        useFractionalInches = p.fractional,
        useTrueNorth = p.trueNorth,
        levelCalibrated = p.levelPitch != null,
        showDiagnostics = p.diagnostics,
        hapticsEnabled = p.haptics,
        keepScreenOn = p.keepOn,
    )
    compassState.value = compassState.value.copy(useTrueNorth = p.trueNorth)
    rulerState.value = rulerState.value.copy(unitSystem = p.system)
    levelState.value = levelState.value.copy(
        unitSystem = p.system,
        isCalibrated = p.levelPitch != null,
    )
    arState.value = arState.value.copy(unitSystem = p.system)
    savedState.value = savedState.value.copy(unitSystem = p.system)
}

internal fun MeasureViewModel.loadPreferencesImpl() {
    applyPreferencesImpl(loadPreferenceValuesImpl())
}

internal fun MeasureViewModel.observeSensorsImpl() {
    viewModelScope.launch {
        orientationManager.orientation.collect { o ->
            if (o == null) return@collect
            compassState.value = compassState.value.copy(
                azimuthTrueDeg = o.azimuthTrueDeg,
                azimuthMagDeg = o.azimuthMagDeg,
                declinationDeg = o.declinationDeg,
                accuracy = o.accuracy,
                hasLocation = o.declinationDeg != 0.0,
            )
        }
    }
    viewModelScope.launch {
        tiltSensor.tilt.collect { t ->
            if (t == null) return@collect
            levelState.value = levelState.value.copy(
                pitchDeg = t.pitchDeg,
                rollDeg = t.rollDeg,
                edgeAngleDeg = t.edgeAngleDeg,
                isFlat = t.isFlat,
                orientation = t.orientation,
                uiRotationDeg = t.uiRotationDeg,
            )
            // Gravity-derived tilt, not the orientation pitch: OrientationManager's
            // remap targets the AR "window" model, where a phone lying flat reads
            // near -90 rather than 0, which would keep this warning permanently on.
            compassState.value = compassState.value.copy(
                tiltWarning = kotlin.math.abs(t.pitchDeg) > MeasureViewModel.COMPASS_TILT_LIMIT_DEG ||
                    kotlin.math.abs(t.rollDeg) > MeasureViewModel.COMPASS_TILT_LIMIT_DEG,
            )
        }
    }
}

// ------------------------------------------------------------------
// Sensor lifecycle + AR anchors + saved measurements + settings + level
// calibration + diagnostics — moved from MeasureViewModel.kt
// (TooManyFunctions split); behavior identical.
// ------------------------------------------------------------------

internal fun MeasureViewModel.startSensorsImpl() {
    orientationManager.start()
    tiltSensor.start()
}

internal fun MeasureViewModel.stopSensorsImpl() {
    orientationManager.stop()
    tiltSensor.stop()
}

/** Set the observer location so magnetic declination can be resolved. */
internal fun MeasureViewModel.updateLocationImpl(lat: Double, lon: Double) {
    orientationManager.updateLocation(lat, lon)
}

/** Ruler scale, taken from the display's reported physical pixel density. */
internal fun MeasureViewModel.setPixelsPerMmImpl(v: Float) {
    if (v > 0f) rulerState.value = rulerState.value.copy(pixelsPerMm = v)
}

internal fun MeasureViewModel.onTrackingUpdateImpl(quality: TrackingQuality, hasPlane: Boolean) {
    arState.value = arState.value.copy(quality = quality, hasPlane = hasPlane)
}

internal fun MeasureViewModel.setCameraPermissionImpl(granted: Boolean) {
    arState.value = arState.value.copy(cameraPermissionGranted = granted)
}

/** Called by the AR page with a world point resolved by the native engine. */
internal fun MeasureViewModel.addResolvedAnchorImpl(x: Double, y: Double, z: Double, onPlane: Boolean) {
    val anchor = Anchor(nextAnchorId++, x, y, z, onPlane)
    val anchors = arState.value.anchors + anchor
    arState.value = arState.value.copy(anchors = anchors).recomputedImpl()
}

internal fun MeasureViewModel.updateDiagnosticsImpl(update: DiagnosticsUiState.() -> DiagnosticsUiState) {
    diagnosticsState.value = diagnosticsState.value.update()
}

/**
 * Recompute derived measurements from the anchor list.
 *
 * Distance is between the last two anchors; area and perimeter only once the
 * polygon is explicitly closed, since an open chain has no meaningful area.
 */
internal fun ArMeasureUiState.recomputedImpl(): ArMeasureUiState {
    val distance = if (anchors.size >= 2) {
        anchors[anchors.size - 2].distanceTo(anchors[anchors.size - 1])
    } else {
        null
    }
    val area = if (polygonClosed && anchors.size >= 3) polygonArea(anchors) else null
    val perimeter = if (polygonClosed && anchors.size >= 3) {
        polygonPerimeter(anchors, closed = true)
    } else {
        null
    }
    return copy(distanceM = distance, areaM2 = area, perimeterM = perimeter)
}
