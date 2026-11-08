package com.vayunmathur.measure.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.measure.domain.HeldOrientation
import com.vayunmathur.measure.platform.sensor.ImuRecorder
import com.vayunmathur.measure.platform.sensor.TiltSensor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
class SensorDevicesTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val sensorManager get() = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private fun event(type: Int, values: FloatArray, timestamp: Long = 0L) =
        SensorEventBuilder.newBuilder()
            .setSensor(ShadowSensor.newInstance(type))
            .setValues(values)
            .setTimestamp(timestamp)
            .build()

    private fun addSensor(type: Int) {
        shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(type))
    }

    // ---- TiltSensor ----

    @Test fun tiltNeedsASensorAndStartsOnce() {
        val tilt = TiltSensor(context)
        // No gravity or accelerometer on the Robolectric device: start is a
        // no-op, stop is a no-op, and no reading ever arrives.
        tilt.start()
        tilt.stop()
        assertNull(tilt.tilt.value)
        tilt.onAccuracyChanged(null, 1)

        addSensor(Sensor.TYPE_GRAVITY)
        tilt.start()
        tilt.start() // already running
        assertTrue(shadowOf(sensorManager).hasListener(tilt))
        tilt.onSensorChanged(event(Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 9.81f)))
        val flat = tilt.tilt.value
        assertNotNull(flat)
        assertTrue(flat.isFlat)
        tilt.stop()
        tilt.stop()
        assertFalse(shadowOf(sensorManager).hasListener(tilt))
    }

    @Test fun tiltSmoothesCalibratesAndReportsOrientation() {
        addSensor(Sensor.TYPE_ACCELEROMETER) // gravity absent: fallback path
        val tilt = TiltSensor(context)
        tilt.start()
        tilt.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0f, 0f, 9.81f)))
        // Resting at an angle: keep feeding the same vector so the low-pass settles.
        repeat(16) { tilt.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER, floatArrayOf(9.81f, 0f, 0f))) }
        val tilted = tilt.tilt.value!!
        assertFalse(tilted.isFlat)
        assertEquals(HeldOrientation.LandscapeLeft, tilted.orientation)

        // Zero against this pose: the same pose now reads level.
        val zero = tilt.captureZero()
        tilt.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER, floatArrayOf(9.81f, 0f, 0f)))
        val levelled = tilt.tilt.value!!
        assertEquals(0.0, levelled.pitchDeg, 0.5)
        assertEquals(0.0, levelled.rollDeg, 0.5)

        // An explicit calibration replaces the captured one; clearing restores raw.
        tilt.setCalibration(1.0, 2.0, 3.0)
        tilt.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER, floatArrayOf(9.81f, 0f, 0f)))
        val offset = tilt.tilt.value!!
        assertEquals(levelled.pitchDeg - 1.0, offset.pitchDeg, 0.6)
        tilt.clearCalibration()
        tilt.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER, floatArrayOf(9.81f, 0f, 0f)))
        assertEquals(levelled.pitchDeg, tilt.tilt.value!!.pitchDeg, 0.6)
        tilt.stop()
    }

    // ---- ImuRecorder ----

    @Test fun imuNeedsBothSensorsAndPairsGyroWithAccel() {
        val imu = ImuRecorder(context)
        imu.start() // nothing to register
        imu.stop()
        assertEquals(0, imu.drain().size)
        assertEquals(0.0, imu.rateHz())
        assertEquals(0L, imu.lastTimestampNs())

        addSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        addSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        imu.start()
        imu.start()
        // Accel before any gyro produces nothing.
        imu.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, floatArrayOf(0f, 0f, 9.81f), timestamp = 100L))
        assertEquals(0, imu.drain().size)
        // Gyro then accel pairs them, packed [t, gx, gy, gz, ax, ay, az].
        imu.onSensorChanged(event(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, floatArrayOf(1f, 2f, 3f)))
        imu.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, floatArrayOf(4f, 5f, 6f), timestamp = 1_000_000_000L))
        imu.onSensorChanged(event(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, floatArrayOf(7f, 8f, 9f)))
        imu.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, floatArrayOf(4f, 5f, 6f), timestamp = 2_000_000_000L))
        val drained = imu.drain()
        assertEquals(14, drained.size)
        assertEquals(1_000_000_000.0, drained[0])
        assertEquals(1.0, drained[1]); assertEquals(2.0, drained[2]); assertEquals(3.0, drained[3])
        assertEquals(4.0, drained[4]); assertEquals(6.0, drained[6])
        assertEquals(7.0, drained[8])
        assertEquals(2_000_000_000L, imu.lastTimestampNs())
        // Two samples over one second.
        assertEquals(2.0, imu.rateHz(), 0.001)
        assertEquals(0, imu.drain().size, "drain empties the buffer")
        imu.onAccuracyChanged(null, 1)
        imu.stop()
        imu.stop()
        // After stop the buffer is cleared and no new samples land.
        imu.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, floatArrayOf(4f, 5f, 6f), timestamp = 3_000_000_000L))
    }

    @Test fun imuFallsBackToCalibratedSensors() {
        addSensor(Sensor.TYPE_GYROSCOPE)
        addSensor(Sensor.TYPE_ACCELEROMETER)
        val imu = ImuRecorder(context)
        imu.start()
        assertTrue(shadowOf(sensorManager).hasListener(imu))
        imu.onSensorChanged(event(Sensor.TYPE_GYROSCOPE, floatArrayOf(1f, 0f, 0f)))
        imu.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0f, 1f, 0f), timestamp = 5L))
        assertEquals(7, imu.drain().size)
        imu.stop()
    }

    @Test fun imuOverflowDropsTheOldestHalf() {
        addSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        addSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        val imu = ImuRecorder(context)
        imu.start()
        imu.onSensorChanged(event(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, floatArrayOf(1f, 2f, 3f)))
        // MAX_PENDING is 7 * 8000; feed past it: sampleCount keeps counting
        // while the buffer drops its oldest half instead of growing.
        val samples = 9_000
        for (i in 0 until samples) {
            imu.onSensorChanged(
                event(
                    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
                    floatArrayOf(0f, 0f, 9.81f),
                    timestamp = (i + 1) * 1_000_000L,
                ),
            )
        }
        val drained = imu.drain()
        assertTrue(drained.size in 1..(7 * 8000), "buffer stayed bounded: ${drained.size}")
        assertEquals(0, drained.size % 7)
        // The newest samples survive: the last timestamp is present.
        assertEquals(9_000_000_000.0, drained[drained.size - 7])
        assertTrue(imu.rateHz() > 0.0)
        imu.stop()
    }

    @Test fun imuRateIsZeroInsideTheMinimumSpan() {
        addSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        addSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        val imu = ImuRecorder(context)
        imu.start()
        imu.onSensorChanged(event(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, floatArrayOf(1f, 2f, 3f)))
        imu.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, floatArrayOf(0f, 0f, 9.81f), timestamp = 1_000L))
        imu.onSensorChanged(event(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, floatArrayOf(0f, 0f, 9.81f), timestamp = 2_000L))
        assertEquals(0.0, imu.rateHz())
        imu.stop()
    }
}
