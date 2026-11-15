package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Constructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-c2: PanoramaEngine gyro integration and guide-dot state machine. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovC2PanoramaSensorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun gyroEvent(timestampNs: Long, x: Float, y: Float, z: Float): SensorEvent {
        val ctor: Constructor<SensorEvent> =
            SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        ctor.isAccessible = true
        val event = ctor.newInstance(3)
        event.values[0] = x
        event.values[1] = y
        event.values[2] = z
        val tsField = SensorEvent::class.java.getDeclaredField("timestamp")
        tsField.isAccessible = true
        tsField.setLong(event, timestampNs)
        val sensorField = SensorEvent::class.java.getDeclaredField("sensor")
        sensorField.isAccessible = true
        sensorField.set(event, gyroSensor())
        return event
    }

    private fun gyroSensor(): Sensor {
        // Robolectric has no default gyro; allocate a Sensor and set its type.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val sensor = allocate.invoke(theUnsafe, Sensor::class.java) as Sensor
        for (name in listOf("mType", "type")) {
            try {
                val field = Sensor::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.setInt(sensor, Sensor.TYPE_GYROSCOPE)
                return sensor
            } catch (_: Exception) {
            }
        }
        return sensor
    }

    @Test fun gyroSweepCapturesAlignedDotsAndCompletes() {
        assertEquals(Sensor.TYPE_GYROSCOPE, gyroSensor().type, "gyro sensor type")
        val engine = PanoramaEngine(context)
        var completed = false
        engine.onSweepComplete = { completed = true }
        engine.startSweep(fullSphere = false)
        assertTrue(engine.isSweeping.value)
        assertEquals(12, engine.guideDots.value.size)

        engine.offerFrame(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), 0)
        // First event only seeds the timestamp.
        engine.onSensorChanged(gyroEvent(1_000_000_000L, 0f, 0f, 0f))
        // Steady at the start angle for one second: dot 0 aligns and captures.
        engine.onSensorChanged(gyroEvent(2_000_000_000L, 0f, 0f, 0f))
        assertEquals(GuideDotState.CAPTURED, engine.guideDots.value[0].state)

        // Yaw at -30 deg/s (values are rad/s; engine negates Y) for one second
        // moves the sweep angle to the second dot.
        val yawRadPerSec = Math.toRadians(-30.0).toFloat()
        engine.offerFrame(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), 90)
        engine.onSensorChanged(gyroEvent(3_000_000_000L, 0f, yawRadPerSec, 0f))
        engine.onSensorChanged(gyroEvent(4_000_000_000L, 0f, 0f, 0f))
        assertTrue(engine.currentAngle.value > 20f, "angle was ${engine.currentAngle.value}")
        assertEquals(GuideDotState.CAPTURED, engine.guideDots.value[1].state)

        // A fast jerk marks nearby dots as aligning/capturing without capture.
        engine.onSensorChanged(gyroEvent(5_000_000_000L, 2f, 0f, 0f))
        // Sweeping past the flat-pano limit completes the sweep.
        engine.onSensorChanged(gyroEvent(6_000_000_000L, 0f, Math.toRadians(-400.0).toFloat(), 0f))
        assertTrue(completed || !engine.isSweeping.value)
        engine.stopSweep()
        assertFalse(engine.isSweeping.value)
    }

    @Test fun sensorEventsIgnoredWhenNotSweeping() {
        val engine = PanoramaEngine(context)
        engine.onSensorChanged(gyroEvent(1_000_000_000L, 1f, 1f, 1f))
        assertEquals(0f, engine.currentAngle.value)
        engine.onAccuracyChanged(null, 0)
    }
}
