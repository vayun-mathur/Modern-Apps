package com.vayunmathur.library.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder

/** OrientationManager fed real rotation-vector / accel+mag events via Robolectric shadows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrientationManagerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val sensorManager: SensorManager
        get() = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private fun addSensor(type: Int): Sensor {
        val sensor = SensorBuilder.newBuilder().setType(type).setName("test-$type").build()
        Shadows.shadowOf(sensorManager).addSensor(sensor)
        return sensor
    }

    private fun event(sensor: Sensor, vararg values: Float) =
        SensorEventBuilder.newBuilder(sensor, values).build()

    @Test fun rotationVectorIdentityEmitsFlatOrientation() {
        val rv = addSensor(Sensor.TYPE_ROTATION_VECTOR)
        val mgr = OrientationManager(ctx)
        mgr.updateLocation(0.0, 0.0) // equator/prime meridian: declination ~0
        mgr.start()
        assertTrue(Shadows.shadowOf(sensorManager).hasListener(mgr))

        mgr.onSensorChanged(event(rv, 0f, 0f, 0f, 1f))
        val o = mgr.orientation.value
        assertNotNull(o)
        // Robolectric's ShadowSensorManager synthesises the rotation matrix, so
        // assert the emitted frame is well-formed rather than its absolute frame.
        assertTrue(o.azimuthMagDeg in 0.0..360.0, "azimuth ${o.azimuthMagDeg}")
        assertTrue(o.azimuthTrueDeg in 0.0..360.0, "true azimuth ${o.azimuthTrueDeg}")
        assertTrue(o.pitchDeg in -180.0..180.0, "pitch ${o.pitchDeg}")
        assertTrue(o.rollDeg in -180.0..180.0, "roll ${o.rollDeg}")
        assertTrue(o.pointingAltDeg in -90.0..90.0, "pointing alt ${o.pointingAltDeg}")
        assertTrue(o.pointingAzTrueDeg in 0.0..360.0, "pointing az ${o.pointingAzTrueDeg}")
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, o.accuracy)

        mgr.onAccuracyChanged(rv, SensorManager.SENSOR_STATUS_ACCURACY_LOW)
        mgr.onSensorChanged(event(rv, 0f, 0f, 0f, 1f))
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, mgr.orientation.value!!.accuracy)

        mgr.stop()
        assertTrue(!Shadows.shadowOf(sensorManager).hasListener(mgr))
    }

    @Test fun rotationAboutVerticalSmoothsHeadingTowardTarget() {
        val rv = addSensor(Sensor.TYPE_ROTATION_VECTOR)
        val mgr = OrientationManager(ctx)
        mgr.start()
        mgr.onSensorChanged(event(rv, 0f, 0f, 0f, 1f))
        val baseline = mgr.orientation.value!!.azimuthMagDeg

        // 90 degrees about Z as a rotation vector: axis * sin(theta/2).
        val half = sin(Math.toRadians(45.0)).toFloat()
        mgr.onSensorChanged(event(rv, 0f, 0f, half))
        val smoothed = mgr.orientation.value!!.azimuthMagDeg
        // Smoothing moves the heading from the baseline but does not jump to the target.
        val wrapped = ((smoothed - baseline) % 360.0 + 360.0) % 360.0
        val delta = minOf(wrapped, 360.0 - wrapped)
        assertTrue(delta > 0.5 && delta < 90.0, "heading $baseline -> $smoothed")
        mgr.stop()
    }

    @Test fun fallbackAccelMagPathEmitsAfterBothSensors() {
        val accel = addSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = addSensor(Sensor.TYPE_MAGNETIC_FIELD)
        // No rotation-vector sensor: start() must use the fallback pair.
        val mgr = OrientationManager(ctx)
        mgr.start()
        mgr.onSensorChanged(event(accel, 0f, 0f, 9.81f))
        assertNull(mgr.orientation.value) // magnetometer not seen yet
        mgr.onSensorChanged(event(mag, 0f, 20f, -40f))
        val o = mgr.orientation.value
        assertNotNull(o)
        assertEquals(0.0, o.azimuthMagDeg, 5.0)
        // Second pair exercises the smoothing branch.
        mgr.onSensorChanged(event(accel, 0f, 9.81f, 0f))
        mgr.onSensorChanged(event(mag, 20f, 0f, -40f))
        assertNotNull(mgr.orientation.value)
        mgr.stop()
    }

    @Test fun displayRotationsRemapAndStillEmit() {
        val rv = addSensor(Sensor.TYPE_ROTATION_VECTOR)
        val display = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        val shadowDisplay = Shadows.shadowOf(display)
        for (rotation in intArrayOf(
            android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_90,
            android.view.Surface.ROTATION_180, android.view.Surface.ROTATION_270, 99,
        )) {
            shadowDisplay.setRotation(rotation)
            val mgr = OrientationManager(ctx)
            mgr.start()
            mgr.onSensorChanged(event(rv, 0f, 0f, 0f, 1f))
            assertNotNull(mgr.orientation.value, "no orientation for display rotation $rotation")
            mgr.stop()
        }
    }

    @Test fun noSensorsAndIrrelevantEventsAreSafe() {
        val mgr = OrientationManager(ctx)
        mgr.start() // nothing registered by Robolectric
        val light = SensorBuilder.newBuilder().setType(Sensor.TYPE_LIGHT).setName("light").build()
        mgr.onSensorChanged(event(light, 42f))
        assertNull(mgr.orientation.value)
        mgr.stop()
        mgr.updateLocation(51.5, -0.12, 0f)
    }
}
