package com.vayunmathur.measure.platform

import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.SavedMeasurement
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.domain.HeldOrientation

/**
 * The UI contract between [MeasureViewModel] and the pages in `ui.pages`.
 *
 * Pages take a state value plus an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from (see `src/screenshotTest`). It lives beside the ViewModel rather than in
 * `ui.pages` so the dependency runs one way: `ui.pages` depends on `ui`, and the ViewModel
 * implements these interfaces.
 *
 * Every actions method has a no-op default body, so [CompassActions.Noop] and friends are
 * the whole implementation a preview needs.
 */

/**
 * Compass dial state. Both azimuths are carried so the dial can switch between them
 * without a round trip to the sensor layer, and [declinationDeg] is shown outright
 * because the difference is the entire reason the toggle exists.
 */
data class CompassUiState(
    val azimuthTrueDeg: Double = 0.0,
    val azimuthMagDeg: Double = 0.0,
    val declinationDeg: Double = 0.0,
    val useTrueNorth: Boolean = true,
    /** Null until a location fix resolves; declination is unknown until then. */
    val hasLocation: Boolean = false,
    /** Mirrors SensorManager.SENSOR_STATUS_* — drives the figure-8 calibration prompt. */
    val accuracy: Int = 3,
    /** Bearing the user has pinned, in degrees, or null when not holding one. */
    val heldBearingDeg: Double? = null,
    val tiltWarning: Boolean = false,
)

interface CompassActions {
    fun setUseTrueNorth(v: Boolean) {}
    fun holdBearing() {}
    fun clearHeldBearing() {}

    companion object {
        val Noop: CompassActions = object : CompassActions {}
    }
}

/**
 * Bubble level state. Every angle is already zero-offset corrected by the binder, so
 * the page draws them directly.
 */
data class LevelUiState(
    val pitchDeg: Double = 0.0,
    val rollDeg: Double = 0.0,
    /**
     * Deviation of the down-facing edge from level, measured against the nearest
     * quarter turn so it reads correctly in portrait, upside down, or on either side.
     */
    val edgeAngleDeg: Double = 0.0,
    /** True when the device is flat enough that the 2D surface bubble is the useful view. */
    val isFlat: Boolean = false,
    val orientation: HeldOrientation = HeldOrientation.Portrait,
    /** Rotation keeping the edge bubble horizontal in the real world. */
    val uiRotationDeg: Float = 0f,
    val isCalibrated: Boolean = false,
    val unitSystem: UnitSystem = UnitSystem.Metric,
)

interface LevelActions {
    fun calibrateZero() {}
    fun clearCalibration() {}

    companion object {
        val Noop: LevelActions = object : LevelActions {}
    }
}

/**
 * On-screen ruler state. [pixelsPerMm] is derived from the display's reported physical
 * pixel density.
 */
data class RulerUiState(
    val pixelsPerMm: Float = 1f,
    val unitSystem: UnitSystem = UnitSystem.Metric,
)

/**
 * AR measurement state.
 *
 * [quality] gates the whole screen: monocular visual-inertial odometry cannot recover
 * metric scale from rotation alone, so until the user has translated the device enough to
 * triangulate, every distance would be scale-free and therefore meaningless. The page shows
 * the initialisation coach instead of anchors while [quality] is [TrackingQuality.Initialising].
 */
data class ArMeasureUiState(
    val quality: TrackingQuality = TrackingQuality.Initialising,
    val anchors: List<Anchor> = emptyList(),
    /** Metres, last segment. Null when fewer than two anchors are placed. */
    val distanceM: Double? = null,
    /** Metres, whole open polyline. Null when fewer than two anchors. */
    val totalDistanceM: Double? = null,
    /** Square metres. Null unless the polygon is closed with three or more anchors. */
    val areaM2: Double? = null,
    /** False when a closed polygon is too bent out of plane to quote an area. */
    val areaTrustworthy: Boolean = true,
    /** Metres. Null unless the polygon is closed. */
    val perimeterM: Double? = null,
    val polygonClosed: Boolean = false,
    /** True once a gravity-aligned plane has been fitted and anchors can snap to it. */
    val hasPlane: Boolean = false,
    val unitSystem: UnitSystem = UnitSystem.Metric,
    val cameraPermissionGranted: Boolean = false,
    /** Typed flow state: the only source the page uses to gate placement. */
    val flowState: com.vayunmathur.measure.domain.ArFlowState =
        com.vayunmathur.measure.domain.ArFlowState.NoSession,
    /** Selected centre-reticle hit, mirrored for the overlay. */
    val reticleHit: com.vayunmathur.measure.domain.HitCandidate? = null,
)

interface ArMeasureActions {
    /** Screen-space tap, normalised to 0..1 on both axes so the page need not know the resolution. */
    fun placeAnchor(nx: Float, ny: Float) {}
    /** Place a point at the current centre reticle hit (the primary placement path). */
    fun placeAtReticle() {}
    fun undoAnchor() {}
    fun removeAnchor(id: Long) {}
    fun clearAnchors() {}
    fun closePolygon() {}
    fun saveCurrentMeasurement(label: String) {}
    fun resetTracking() {}

    companion object {
        val Noop: ArMeasureActions = object : ArMeasureActions {}
    }
}

/** Saved measurement list state. */
data class SavedUiState(
    val measurements: List<SavedMeasurement> = emptyList(),
    val unitSystem: UnitSystem = UnitSystem.Metric,
)

interface SavedActions {
    fun delete(id: Long) {}
    fun rename(id: Long, label: String) {}
    fun exportAll() {}

    companion object {
        val Noop: SavedActions = object : SavedActions {}
    }
}

/** Everything the settings screen draws. */
data class SettingsUiState(
    val unitSystem: UnitSystem = UnitSystem.Metric,
    /** Show imperial lengths as 1/16" fractions rather than decimal inches. */
    val useFractionalInches: Boolean = true,
    val useTrueNorth: Boolean = true,
    val levelCalibrated: Boolean = false,
    val showDiagnostics: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
)

interface SettingsActions {
    fun setUnitSystem(v: UnitSystem) {}
    fun setUseFractionalInches(v: Boolean) {}
    fun setUseTrueNorth(v: Boolean) {}
    fun setShowDiagnostics(v: Boolean) {}
    fun setHapticsEnabled(v: Boolean) {}
    fun setKeepScreenOn(v: Boolean) {}
    fun clearLevelCalibration() {}

    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}

/**
 * Diagnostics state, shown only when [SettingsUiState.showDiagnostics] is on.
 *
 * [timestampSkewMs] is the gap between the camera frame clock and the IMU clock. On devices
 * reporting SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME this should sit near zero; a large or
 * drifting value means the two streams cannot be fused and tracking will not converge.
 */
data class DiagnosticsUiState(
    val featureCount: Int = 0,
    val trackedCount: Int = 0,
    val imuRateHz: Double = 0.0,
    val frameRateHz: Double = 0.0,
    val timestampSkewMs: Double = 0.0,
    val nativeEngineAvailable: Boolean = false,
    val focalPx: Double = 0.0,
    val principalPointPx: Pair<Double, Double> = 0.0 to 0.0,
    val landmarkCount: Int = 0,
    val scaleConfidence: Double = 0.0,
)

interface DiagnosticsActions {
    fun resetTracking() {}

    companion object {
        val Noop: DiagnosticsActions = object : DiagnosticsActions {}
    }
}
