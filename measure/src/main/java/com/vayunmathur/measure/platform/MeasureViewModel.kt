package com.vayunmathur.measure.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.sensor.OrientationManager
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.measure.R
import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.MeasurementKind
import com.vayunmathur.measure.data.model.SavedMeasurement
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.data.model.distanceTo
import com.vayunmathur.measure.domain.MeasureNative
import com.vayunmathur.measure.platform.sensor.ImuRecorder
import com.vayunmathur.measure.platform.sensor.TiltSensor
import com.vayunmathur.measure.domain.polygonArea
import com.vayunmathur.measure.domain.polygonPerimeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the sensor lifecycles and the VIO session, and implements every actions
 * interface in the UI contract so pages can stay previewable.
 */
class MeasureViewModel(app: Application) : AndroidViewModel(app),
    CompassActions, LevelActions,
    ArMeasureActions, SavedActions, SettingsActions, DiagnosticsActions {

    internal val ds = DataStoreUtils.getInstance(app)

    internal val orientationManager = OrientationManager(app)
    internal val tiltSensor = TiltSensor(app)
    internal val imuRecorder = ImuRecorder(app)

    internal val compassState = MutableStateFlow(CompassUiState())
    val compass: StateFlow<CompassUiState> = compassState

    internal val levelState = MutableStateFlow(LevelUiState())
    val level: StateFlow<LevelUiState> = levelState

    internal val rulerState = MutableStateFlow(RulerUiState())
    val ruler: StateFlow<RulerUiState> = rulerState

    internal val arState = MutableStateFlow(ArMeasureUiState())
    val ar: StateFlow<ArMeasureUiState> = arState

    internal val savedState = MutableStateFlow(SavedUiState())
    val saved: StateFlow<SavedUiState> = savedState

    internal val settingsState = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = settingsState

    internal val diagnosticsState = MutableStateFlow(DiagnosticsUiState())
    val diagnostics: StateFlow<DiagnosticsUiState> = diagnosticsState

    /** Native VIO session handle, mirrored from the AR page so reset can reach it. */
    internal var nativeSessionHandle: Long = 0L

    internal var nextAnchorId = 1L
    internal var nextMeasurementId = 1L

    init {
        loadPreferencesImpl()
        observeSensorsImpl()
    }

    /** Set the observer location so magnetic declination can be resolved. */
    fun updateLocation(lat: Double, lon: Double) = updateLocationImpl(lat, lon)

    // ---- CompassActions ----

    override fun setUseTrueNorth(v: Boolean) {
        compassState.value = compassState.value.copy(useTrueNorth = v)
        settingsState.value = settingsState.value.copy(useTrueNorth = v)
        persistBoolean(KEY_TRUE_NORTH, v)
    }

    override fun holdBearing() {
        val s = compassState.value
        val bearing = if (s.useTrueNorth) s.azimuthTrueDeg else s.azimuthMagDeg
        compassState.value = s.copy(heldBearingDeg = bearing)
    }

    override fun clearHeldBearing() {
        compassState.value = compassState.value.copy(heldBearingDeg = null)
    }

    // ---- LevelActions ----

    override fun calibrateZero() {
        val (pitch, roll, edge) = tiltSensor.captureZero()
        levelState.value = levelState.value.copy(isCalibrated = true)
        settingsState.value = settingsState.value.copy(levelCalibrated = true)
        viewModelScope.launch {
            ds.setDouble(KEY_LEVEL_PITCH, pitch)
            ds.setDouble(KEY_LEVEL_ROLL, roll)
            ds.setDouble(KEY_LEVEL_EDGE, edge)
        }
    }

    override fun clearCalibration() {
        tiltSensor.clearCalibration()
        levelState.value = levelState.value.copy(isCalibrated = false)
        settingsState.value = settingsState.value.copy(levelCalibrated = false)
        viewModelScope.launch {
            // Remove the keys rather than writing NaN sentinels: a stored
            // NaN reads back as a non-null Double, so the loader treated
            // the level as calibrated forever and subtracted NaN offsets
            // from every reading after a restart.
            ds.removeKeys(listOf(KEY_LEVEL_PITCH, KEY_LEVEL_ROLL, KEY_LEVEL_EDGE))
        }
    }

    /** Ruler scale, taken from the display's reported physical pixel density. */
    // ---- ArMeasureActions ----

    fun onTrackingUpdate(quality: TrackingQuality, hasPlane: Boolean) = onTrackingUpdateImpl(quality, hasPlane)

    fun setCameraPermission(granted: Boolean) = setCameraPermissionImpl(granted)

    /** Called by the AR page with a world point resolved by the native engine. */
    fun addResolvedAnchor(x: Double, y: Double, z: Double, onPlane: Boolean) = addResolvedAnchorImpl(x, y, z, onPlane)

    override fun undoAnchor() {
        val anchors = arState.value.anchors.dropLast(1)
        arState.value = arState.value.copy(anchors = anchors, polygonClosed = false).recomputedImpl()
    }

    override fun clearAnchors() {
        arState.value = arState.value.copy(
            anchors = emptyList(),
            distanceM = null,
            areaM2 = null,
            perimeterM = null,
            polygonClosed = false,
        )
    }

    override fun closePolygon() {
        if (arState.value.anchors.size < MeasureViewModel.MIN_POLYGON_ANCHORS) return
        arState.value = arState.value.copy(polygonClosed = true).recomputedImpl()
    }

    override fun saveCurrentMeasurement(label: String) {
        val s = arState.value
        val (kind, value) = when {
            s.polygonClosed && s.areaM2 != null -> MeasurementKind.Area to s.areaM2
            s.distanceM != null -> MeasurementKind.Distance to s.distanceM
            else -> return
        }
        val m = SavedMeasurement(
            id = nextMeasurementId++,
            label = label.ifBlank {
                getApplication<Application>().getString(R.string.saved_default_label)
            },
            kind = kind,
            value = value,
            recordedAtEpochMs = System.currentTimeMillis(),
        )
        savedState.value = savedState.value.copy(measurements = savedState.value.measurements + m)
    }

    override fun resetTracking() {
        // Actually reset the engine, not just the UI: without this the
        // diagnostics Reset button cleared the anchor list while the
        // native session kept its (possibly diverged) pose and map.
        MeasureNative.reset(nativeSessionHandle)
        clearAnchors()
        arState.value = arState.value.copy(quality = TrackingQuality.Initialising, hasPlane = false)
    }

    // ---- SavedActions ----

    override fun delete(id: Long) {
        savedState.value = savedState.value.copy(
            measurements = savedState.value.measurements.filterNot { it.id == id }
        )
    }

    override fun rename(id: Long, label: String) {
        savedState.value = savedState.value.copy(
            measurements = savedState.value.measurements.map {
                if (it.id == id) it.copy(label = label) else it
            }
        )
    }

    // ---- SettingsActions ----

    override fun setUnitSystem(v: UnitSystem) {
        settingsState.value = settingsState.value.copy(unitSystem = v)
        rulerState.value = rulerState.value.copy(unitSystem = v)
        levelState.value = levelState.value.copy(unitSystem = v)
        arState.value = arState.value.copy(unitSystem = v)
        savedState.value = savedState.value.copy(unitSystem = v)
        persistBoolean(KEY_IMPERIAL, v == UnitSystem.Imperial)
    }

    override fun setUseFractionalInches(v: Boolean) {
        settingsState.value = settingsState.value.copy(useFractionalInches = v)
        persistBoolean(KEY_FRACTIONAL, v)
    }

    override fun setShowDiagnostics(v: Boolean) {
        settingsState.value = settingsState.value.copy(showDiagnostics = v)
        persistBoolean(KEY_DIAGNOSTICS, v)
    }

    override fun setHapticsEnabled(v: Boolean) {
        settingsState.value = settingsState.value.copy(hapticsEnabled = v)
        persistBoolean(KEY_HAPTICS, v)
    }

    override fun setKeepScreenOn(v: Boolean) {
        settingsState.value = settingsState.value.copy(keepScreenOn = v)
        persistBoolean(KEY_KEEP_SCREEN_ON, v)
    }

    override fun clearLevelCalibration() = clearCalibration()

    // ---- DiagnosticsActions ----

    private fun persistBoolean(key: String, value: Boolean) {
        viewModelScope.launch { ds.setBoolean(key, value) }
    }

    override fun onCleared() {
        stopSensorsImpl()
        imuRecorder.stop()
    }

    companion object {
        internal const val COMPASS_TILT_LIMIT_DEG = 35.0
        /** Anchors needed before a polygon can be closed (area/perimeter need 3+). */
        internal const val MIN_POLYGON_ANCHORS = 3
    }
}

/** Preference keys for the measure settings (used by the Ops extensions). */
internal const val KEY_IMPERIAL = "measure_imperial"
internal const val KEY_FRACTIONAL = "measure_fractional_inches"
internal const val KEY_TRUE_NORTH = "measure_true_north"
internal const val KEY_DIAGNOSTICS = "measure_show_diagnostics"
internal const val KEY_HAPTICS = "measure_haptics"
internal const val KEY_KEEP_SCREEN_ON = "measure_keep_screen_on"
internal const val KEY_LEVEL_PITCH = "measure_level_pitch_offset"
internal const val KEY_LEVEL_ROLL = "measure_level_roll_offset"
internal const val KEY_LEVEL_EDGE = "measure_level_edge_offset"

/** Quality code from the native engine mapped onto the UI enum. */
fun trackingQualityFrom(code: Int): TrackingQuality = when (code) {
    MeasureNative.QUALITY_LIMITED -> TrackingQuality.Limited
    MeasureNative.QUALITY_GOOD -> TrackingQuality.Good
    MeasureNative.QUALITY_LOST -> TrackingQuality.Lost
    else -> TrackingQuality.Initialising
}
