package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNull
import org.junit.runner.RunWith

/**
 * The framework location feed: start/stop lifecycle, the cold-start seed,
 * the stationary gate and the compass path.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class FrameworkLocationManagerSlice2Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `start with no providers enabled yields no fix and stops cleanly`() {
        val manager = FrameworkLocationManager(context)
        val updates = mutableListOf<Pair<com.vayunmathur.library.map.GeoPoint, Float?>>()
        var accuracy = -1
        val listener = manager.startUpdates(
            onUpdateReceived = { point, heading, _ -> updates.add(point to heading) },
            onAccuracyReceived = { accuracy = it },
        )
        assertTrue(listener != null)
        // No provider is enabled under Robolectric and there is no last-known
        // fix, so nothing is emitted — but the listener is live.
        assertEquals(0, updates.size)
        assertEquals(-1, accuracy)
        manager.stop()
    }

    @Test
    fun `stop without start is safe`() {
        FrameworkLocationManager(context).stop()
    }

    @Test
    fun `a restart after stop emits nothing stale`() {
        val manager = FrameworkLocationManager(context)
        val updates = mutableListOf<Pair<com.vayunmathur.library.map.GeoPoint, Float?>>()
        manager.startUpdates(onUpdateReceived = { point, heading, _ -> updates.add(point to heading) })
        manager.stop()
        manager.startUpdates(onUpdateReceived = { point, heading, _ -> updates.add(point to heading) })
        assertEquals(0, updates.size)
        manager.stop()
    }

    @Test
    fun `magnetometer accuracy reaches the accuracy callback`() {
        val manager = FrameworkLocationManager(context)
        var accuracy = -1
        manager.startUpdates(onUpdateReceived = { _, _, _ -> }, onAccuracyReceived = { accuracy = it })
        val sensorManager =
            context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val mag = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)
        if (mag != null) {
            manager.onAccuracyChanged(mag, android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
            assertEquals(android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH, accuracy)
        }
        manager.stop()
    }

    @Test
    fun `accelerometer accuracy never reaches the callback`() {
        val manager = FrameworkLocationManager(context)
        var accuracy = -1
        manager.startUpdates(onUpdateReceived = { _, _, _ -> }, onAccuracyReceived = { accuracy = it })
        val sensorManager =
            context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val acc = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (acc != null) {
            manager.onAccuracyChanged(acc, android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
            assertEquals(-1, accuracy)
        }
        manager.stop()
    }

    @Test
    fun `a null sensor never reaches the callback`() {
        val manager = FrameworkLocationManager(context)
        var accuracy = -1
        manager.startUpdates(onUpdateReceived = { _, _, _ -> }, onAccuracyReceived = { accuracy = it })
        manager.onAccuracyChanged(null, android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        assertEquals(-1, accuracy)
        manager.stop()
    }
}
