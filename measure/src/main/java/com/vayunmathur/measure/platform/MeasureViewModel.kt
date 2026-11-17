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
import com.vayunmathur.measure.platform.vio.VioEngine
import com.vayunmathur.measure.platform.sensor.ImuRecorder
import com.vayunmathur.measure.platform.sensor.TiltSensor
import com.vayunmathur.measure.domain.polygonArea
import com.vayunmathur.measure.domain.polygonPerimeter
import kotlinx.coroutines.Dispatchers
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

    /** Rust VIO engine, mirrored from the AR page so reset can reach it. */
    internal var vioEngine: VioEngine? = null

    /**
     * Whether the tracking engine can run here. A seam (not a direct
     * static read) so flow tests can simulate a capable device:
     * native availability is false on every JVM test host.
     */
    internal var engineAvailable: Boolean = VioEngine.available

    internal var scanningSinceMs: Long? = null
    internal var lostSinceMs: Long? = null
    internal var unavailableReason: com.vayunmathur.measure.domain.ArUnavailableReason? = null

    /** Hit provider seam; the page swaps in the native-backed one. */
    var hitProvider: ArHitProvider = FakeArHitProvider()

    internal var nextAnchorId = 1L
    internal var nextMeasurementId = 1L

    init {
        loadPreferencesImpl()
        observeSensorsImpl()
    }

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
        // IO, not Main: a DataStore edit suspended on the main
        // dispatcher can strand the store's write lock if that
        // dispatcher stops being pumped (Robolectric resets it
        // between tests, wedging every later edit in the process).
        viewModelScope.launch(Dispatchers.IO) {
            ds.setDouble(KEY_LEVEL_PITCH, pitch)
            ds.setDouble(KEY_LEVEL_ROLL, roll)
            ds.setDouble(KEY_LEVEL_EDGE, edge)
        }
    }

    override fun clearCalibration() {
        tiltSensor.clearCalibration()
        levelState.value = levelState.value.copy(isCalibrated = false)
        settingsState.value = settingsState.value.copy(levelCalibrated = false)
        viewModelScope.launch(Dispatchers.IO) {
            // Remove the keys rather than writing NaN sentinels: a stored
            // NaN reads back as a non-null Double, so the loader treated
            // the level as calibrated forever and subtracted NaN offsets
            // from every reading after a restart.
            ds.removeKeys(listOf(KEY_LEVEL_PITCH, KEY_LEVEL_ROLL, KEY_LEVEL_EDGE))
        }
    }

    /** Ruler scale, taken from the display's reported physical pixel density. */
    // ---- ArMeasureActions ----

    override fun placeAtReticle() {
        val hit = arState.value.reticleHit ?: return
        if (!com.vayunmathur.measure.domain.ArFlow.canPlace(arState.value.flowState)) return
        addResolvedAnchor(
            hit.position.x, hit.position.y, hit.position.z,
            onPlane = hit.kind == com.vayunmathur.measure.domain.HitKind.Plane,
        )
    }

    override fun removeAnchor(id: Long) {
        val anchors = arState.value.anchors.filterNot { it.id == id }
        arState.value = arState.value.copy(anchors = anchors, polygonClosed = false)
            .recomputedImpl()
    }

    override fun undoAnchor() {
        val anchors = arState.value.anchors.dropLast(1)
        arState.value = arState.value.copy(anchors = anchors, polygonClosed = false).recomputedImpl()
    }

    override fun clearAnchors() {
        arState.value = arState.value.copy(
            anchors = emptyList(),
            distanceM = null,
            totalDistanceM = null,
            areaM2 = null,
            areaTrustworthy = true,
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
        // engine kept its (possibly diverged) pose and map.
        vioEngine?.reset()
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
        // IO for the same reason as calibrateZero: never hold a
        // DataStore edit on the main dispatcher.
        viewModelScope.launch(Dispatchers.IO) { ds.setBoolean(key, value) }
    }

    override fun onCleared() {
        stopSensorsImpl()
        imuRecorder.stop()
    }

    /** Tuning constants for [MeasureViewModel], including the compass tilt limit and minimum polygon size. */
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
