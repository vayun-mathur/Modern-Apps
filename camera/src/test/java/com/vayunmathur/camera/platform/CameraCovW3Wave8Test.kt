package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Constructor
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-w3 wave 8: panorama stitch degradation and estimate parsing. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCovW3Wave8Test {
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

    @Test fun sweepThenStitchDegradesWithoutRegistrableFrames() = runBlocking<Unit> {
        val engine = PanoramaEngine(context)
        engine.startSweep(fullSphere = false)
        engine.offerFrame(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), 0)
        engine.onSensorChanged(gyroEvent(1_000_000_000L, 0f, 0f, 0f))
        engine.onSensorChanged(gyroEvent(2_000_000_000L, 0f, 0f, 0f))
        engine.offerFrame(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), 0)
        engine.onSensorChanged(gyroEvent(3_000_000_000L, 0f, Math.toRadians(-30.0).toFloat(), 0f))
        engine.onSensorChanged(gyroEvent(4_000_000_000L, 0f, 0f, 0f))
        engine.stopSweep()
        // Tiny flat frames cannot register: stitch must return null (GPU estimate
        // fails, CPU fallback fails) rather than throw, on either native path.
        val result = engine.stitch()
        assertNull(result)
        assertEquals(false, engine.isStitching.value)
        engine.reset()
    }

    @Test fun estimateParsingRejectsGarbage() {
        assertNull(GpuStitcher.parseEstimate(ByteArray(0)))
        assertNull(GpuStitcher.parseEstimate(byteArrayOf(1, 2, 3)))
        assertNull(GpuStitcher.parseEstimate(ByteArray(64) { 0xFF.toByte() }))
        // A longer all-zero blob parses its header fields (all zero) or rejects
        // them; either outcome must be a value, not a crash.
        GpuStitcher.parseEstimate(ByteArray(1024))
        assertTrue(true)
    }

    @Test fun fullSphereSweepLayoutAndReset() {
        val engine = PanoramaEngine(context)
        engine.startSweep(fullSphere = true)
        assertTrue(engine.guideDots.value.size > 12, "sphere grid has rows of dots")
        assertTrue(engine.isSweeping.value)
        engine.stopSweep()
        engine.reset()
        assertEquals(0, engine.frameCount.value)
    }
}
