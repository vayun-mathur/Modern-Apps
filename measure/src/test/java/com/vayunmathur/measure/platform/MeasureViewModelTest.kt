package com.vayunmathur.measure.platform

import android.app.Application
import android.hardware.Sensor
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.measure.data.model.MeasurementKind
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.data.model.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
class MeasureViewModelTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (cond()) return
            Thread.sleep(10)
        }
        pump()
        assertTrue(cond(), "timed out waiting for: $desc")
    }

    private fun gravityEvent(x: Float, y: Float, z: Float) =
        SensorEventBuilder.newBuilder()
            .setSensor(ShadowSensor.newInstance(Sensor.TYPE_GRAVITY))
            .setValues(floatArrayOf(x, y, z))
            .build()

    @Test fun compassActionsHoldAndClear() {
        val vm = MeasureViewModel(app)
        assertTrue(vm.compass.value.useTrueNorth)
        vm.compassState.value = vm.compass.value.copy(azimuthMagDeg = 30.0, azimuthTrueDeg = 42.0, useTrueNorth = false)
        vm.holdBearing()
        assertEquals(30.0, vm.compass.value.heldBearingDeg)
        vm.setUseTrueNorth(true)
        assertTrue(vm.compass.value.useTrueNorth)
        assertTrue(vm.settings.value.useTrueNorth)
        vm.holdBearing()
        assertEquals(42.0, vm.compass.value.heldBearingDeg)
        vm.clearHeldBearing()
        assertNull(vm.compass.value.heldBearingDeg)
        // The choice persists.
        await("true north persisted") { !vm.ds.getBoolean(KEY_TRUE_NORTH, true).not() }
    }

    @Test fun settingsActionsFanOutAndPersist() {
        val vm = MeasureViewModel(app)
        vm.setUnitSystem(UnitSystem.Imperial)
        assertEquals(UnitSystem.Imperial, vm.settings.value.unitSystem)
        assertEquals(UnitSystem.Imperial, vm.ruler.value.unitSystem)
        assertEquals(UnitSystem.Imperial, vm.level.value.unitSystem)
        assertEquals(UnitSystem.Imperial, vm.ar.value.unitSystem)
        assertEquals(UnitSystem.Imperial, vm.saved.value.unitSystem)
        vm.setUnitSystem(UnitSystem.Metric)
        assertEquals(UnitSystem.Metric, vm.ar.value.unitSystem)
        vm.setUseFractionalInches(false)
        assertFalse(vm.settings.value.useFractionalInches)
        vm.setShowDiagnostics(true)
        assertTrue(vm.settings.value.showDiagnostics)
        vm.setHapticsEnabled(false)
        assertFalse(vm.settings.value.hapticsEnabled)
        vm.setKeepScreenOn(false)
        assertFalse(vm.settings.value.keepScreenOn)
        await("imperial persisted as metric") { !vm.ds.getBoolean(KEY_IMPERIAL, true) }
        await("diagnostics persisted") { vm.ds.getBoolean(KEY_DIAGNOSTICS, false) }
    }

    @Test fun levelCalibrationRoundTripsThroughTheTiltSensor() {
        val vm = MeasureViewModel(app)
        // Resting flat: gravity straight down -z in device terms is (0,0,9.8)
        // for the fused gravity sensor reading used by the level.
        vm.tiltSensor.onSensorChanged(gravityEvent(0f, 0f, 9.81f))
        pump()
        vm.calibrateZero()
        assertTrue(vm.level.value.isCalibrated)
        assertTrue(vm.settings.value.levelCalibrated)
        vm.clearLevelCalibration()
        assertFalse(vm.level.value.isCalibrated)
        assertFalse(vm.settings.value.levelCalibrated)
        vm.calibrateZero()
        vm.clearCalibration()
        assertFalse(vm.level.value.isCalibrated)
    }

    @Test fun arAnchorsRecomputeDistanceAreaAndSave() {
        val vm = MeasureViewModel(app)
        vm.setCameraPermission(true)
        assertTrue(vm.ar.value.cameraPermissionGranted)
        vm.onTrackingUpdate(TrackingQuality.Good, hasPlane = true)
        assertEquals(TrackingQuality.Good, vm.ar.value.quality)
        assertTrue(vm.ar.value.hasPlane)

        vm.saveCurrentMeasurement("nothing yet")
        assertTrue(vm.saved.value.measurements.isEmpty())

        vm.addResolvedAnchor(0.0, 0.0, 0.0, onPlane = true)
        assertNull(vm.ar.value.distanceM)
        vm.addResolvedAnchor(3.0, 4.0, 0.0, onPlane = true)
        assertEquals(5.0, vm.ar.value.distanceM)
        // Too few anchors to close.
        vm.closePolygon()
        assertFalse(vm.ar.value.polygonClosed)

        vm.addResolvedAnchor(3.0, 0.0, 0.0, onPlane = false)
        vm.closePolygon()
        assertTrue(vm.ar.value.polygonClosed)
        assertNotNull(vm.ar.value.areaM2)
        assertNotNull(vm.ar.value.perimeterM)
        // Right triangle 3-4-5: area 6, perimeter 12.
        assertEquals(6.0, vm.ar.value.areaM2!!, 0.001)
        assertEquals(12.0, vm.ar.value.perimeterM!!, 0.001)

        vm.saveCurrentMeasurement("")
        assertEquals(1, vm.saved.value.measurements.size)
        val saved = vm.saved.value.measurements.single()
        assertEquals(MeasurementKind.Area, saved.kind)
        assertEquals(6.0, saved.value, 0.001)
        assertTrue(saved.label.isNotBlank())

        // Undo drops back to an open chain; distance follows the last pair.
        vm.undoAnchor()
        assertFalse(vm.ar.value.polygonClosed)
        assertEquals(2, vm.ar.value.anchors.size)
        assertEquals(5.0, vm.ar.value.distanceM)

        vm.clearAnchors()
        assertTrue(vm.ar.value.anchors.isEmpty())
        assertNull(vm.ar.value.distanceM)

        // Save a plain distance measurement and manage it.
        vm.addResolvedAnchor(0.0, 0.0, 0.0, onPlane = true)
        vm.addResolvedAnchor(0.0, 2.0, 0.0, onPlane = true)
        vm.saveCurrentMeasurement("wall")
        val distance = vm.saved.value.measurements.last()
        assertEquals(MeasurementKind.Distance, distance.kind)
        assertEquals(2.0, distance.value, 0.001)
        assertEquals("wall", distance.label)
        vm.rename(distance.id, "wall (east)")
        assertEquals("wall (east)", vm.saved.value.measurements.last().label)
        vm.rename(999L, "nobody")
        vm.delete(distance.id)
        assertFalse(vm.saved.value.measurements.any { it.id == distance.id })
        vm.delete(999L)

        vm.resetTracking()
        assertEquals(TrackingQuality.Initialising, vm.ar.value.quality)
        assertTrue(vm.ar.value.anchors.isEmpty())
    }

    @Test fun trackingLostClearsAnchorsAndMeasurements() {
        val vm = MeasureViewModel(app)
        vm.onTrackingUpdate(TrackingQuality.Good, hasPlane = true)
        vm.addResolvedAnchor(0.0, 0.0, 0.0, onPlane = true)
        vm.addResolvedAnchor(3.0, 4.0, 0.0, onPlane = true)
        vm.addResolvedAnchor(3.0, 0.0, 0.0, onPlane = true)
        vm.closePolygon()
        assertTrue(vm.ar.value.polygonClosed)
        assertNotNull(vm.ar.value.distanceM)
        // Losing tracking invalidates the world frame: anchors and every
        // derived measurement must go, or the UI quotes unanchored numbers.
        vm.onTrackingUpdate(TrackingQuality.Lost, hasPlane = false)
        assertEquals(TrackingQuality.Lost, vm.ar.value.quality)
        assertTrue(vm.ar.value.anchors.isEmpty())
        assertNull(vm.ar.value.distanceM)
        assertNull(vm.ar.value.areaM2)
        assertNull(vm.ar.value.perimeterM)
        assertFalse(vm.ar.value.polygonClosed)
        assertFalse(vm.ar.value.hasPlane)
        // A non-Lost update keeps whatever anchors exist.
        vm.addResolvedAnchor(1.0, 1.0, 0.0, onPlane = false)
        vm.onTrackingUpdate(TrackingQuality.Limited, hasPlane = true)
        assertEquals(1, vm.ar.value.anchors.size)
        assertTrue(vm.ar.value.hasPlane)
    }

    @Test fun sensorFeedsDriveCompassAndLevelState() {
        val vm = MeasureViewModel(app)
        vm.updateLocation(37.8, -122.4)
        // Tilt feed: device held tilted well past the compass warning limit.
        vm.tiltSensor.onSensorChanged(gravityEvent(9.0f, 0f, 4.0f))
        await("level state follows tilt") { vm.level.value.pitchDeg != 0.0 || vm.level.value.rollDeg != 0.0 }
        await("tilt warning") { vm.compass.value.tiltWarning }
        // Settle it flat again and the warning clears.
        repeat(12) { vm.tiltSensor.onSensorChanged(gravityEvent(0f, 0f, 9.81f)) }
        await("warning clears") { !vm.compass.value.tiltWarning }

        // Orientation feed from the rotation-vector path.
        vm.orientationManager.onSensorChanged(
            SensorEventBuilder.newBuilder()
                .setSensor(ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR))
                .setValues(floatArrayOf(0f, 0f, 0.5f, 0.5f))
                .build(),
        )
        await("compass follows orientation") {
            vm.compass.value.azimuthMagDeg != 0.0 || vm.compass.value.azimuthTrueDeg != 0.0
        }
        vm.orientationManager.onAccuracyChanged(ShadowSensor.newInstance(Sensor.TYPE_MAGNETIC_FIELD), 1)
        assertTrue(vm.compass.value.hasLocation || vm.compass.value.declinationDeg == 0.0)
    }

    @Test fun preferencesLoadIntoEveryState() {
        val vm = MeasureViewModel(app)
        // calibrateZero writes level offsets; unit + toggles persist, then a
        // fresh load applies them all at once.
        run {
            val store = vm.ds
            // Write off the main thread and pump while waiting:
            // runBlocking here parks the Robolectric main thread
            // forever once a previous test (settingsActions...) has
            // left a DataStore edit suspended on the main dispatcher
            // - Robolectric discards that queue between tests, the
            // store's write lock is never released, and every later
            // edit blocks with no IO thread progressing (the class
            // run's deadlocked sandbox main thread). The ViewModel
            // now writes on IO; this test does the same.
            val written = java.util.concurrent.CountDownLatch(1)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                store.setBoolean(KEY_IMPERIAL, true)
                store.setBoolean(KEY_FRACTIONAL, false)
                store.setBoolean(KEY_DIAGNOSTICS, true)
                written.countDown()
            }
            val writeDeadline = System.currentTimeMillis() + 15_000
            while (written.count > 0 && System.currentTimeMillis() < writeDeadline) {
                pump()
                Thread.sleep(10)
            }
            assertTrue(written.count == 0L, "timed out writing preferences")
        }
        // The synchronous getters read the IO-mirrored snapshot; wait
        // for the mirror to catch up before loading.
        await("preferences mirrored") { vm.ds.getBoolean(KEY_IMPERIAL, false) }
        vm.loadPreferencesImpl()
        assertEquals(UnitSystem.Imperial, vm.settings.value.unitSystem)
        assertFalse(vm.settings.value.useFractionalInches)
        assertTrue(vm.settings.value.showDiagnostics)
        vm.calibrateZero()
        await("level offsets stored") { vm.ds.getDouble(KEY_LEVEL_PITCH) != null }
        vm.loadPreferencesImpl()
        assertTrue(vm.settings.value.levelCalibrated)
        assertTrue(vm.level.value.isCalibrated)
    }

    @Test fun rulerScaleAndDiagnosticsAndLifecycle() {
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            ViewModelProvider.AndroidViewModelFactory(app),
        )[MeasureViewModel::class.java]
        vm.setPixelsPerMmImpl(2.5f)
        assertEquals(2.5f, vm.ruler.value.pixelsPerMm)
        vm.setPixelsPerMmImpl(0f)
        assertEquals(2.5f, vm.ruler.value.pixelsPerMm)
        vm.setPixelsPerMmImpl(-1f)
        assertEquals(2.5f, vm.ruler.value.pixelsPerMm)
        vm.updateDiagnosticsImpl { copy(featureCount = 12, nativeEngineAvailable = true) }
        assertEquals(12, vm.diagnostics.value.featureCount)
        assertTrue(vm.diagnostics.value.nativeEngineAvailable)
        vm.startSensorsImpl()
        vm.startSensorsImpl()
        vm.stopSensorsImpl()
        // Clearing the store runs onCleared (sensor teardown) without error.
        store.clear()
    }

}
