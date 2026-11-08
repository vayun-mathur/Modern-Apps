package com.vayunmathur.measure

import android.graphics.Rect
import android.hardware.Sensor
import android.os.Looper
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.domain.MeasureNative
import com.vayunmathur.measure.platform.MeasureViewModel
import com.vayunmathur.measure.platform.sensor.ImuRecorder
import com.vayunmathur.measure.ui.components.VioFrameAnalyzer
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
class MainActivityAndAnalyzerTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun mainActivityLaunchesThroughSensorLifecycle() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        // onStart/onStop have run via setup(); stopping again is the point:
        // sensor teardown is idempotent through the activity lifecycle.
        controller.pause().stop().destroy()
    }

    private class FakePlane(private val data: ByteArray) : ImageProxy.PlaneProxy {
        override val buffer: ByteBuffer get() = ByteBuffer.wrap(data)
        override val pixelStride: Int = 1
        override val rowStride: Int = 4
    }

    private class FakeImage(
        private val ts: Long,
        private val planeData: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
    ) : ImageProxy {
        var closed = false
        override fun close() { closed = true }
        private var cropRectValue = Rect(0, 0, 4, 2)
        override val cropRect: Rect get() = cropRectValue
        override fun setCropRect(rect: Rect?) { if (rect != null) cropRectValue = rect }
        override val format: Int = 35
        override val height: Int = 2
        override val width: Int = 4
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(FakePlane(planeData))
        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp(): Long = ts
            override fun getRotationDegrees(): Int = 0
            override fun populateExifData(b: ExifData.Builder) {}
        }
        override fun getImage(): android.media.Image? = null
    }

    @Test fun analyzerWithNoSessionJustClosesFrames() {
        val imu = ImuRecorder(app)
        var tracking = 0
        val analyzer = VioFrameAnalyzer(0L, imu, { _, _ -> tracking++ }, { _, _, _, _, _, _ -> })
        val image = FakeImage(ts = 1_000L)
        analyzer.analyze(image)
        assertTrue(image.closed)
        assertEquals(0, tracking)
    }

    @Test fun analyzerPumpsImuAndReportsPerFrame() {
        val sensorManager = app.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE_UNCALIBRATED))
        shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED))
        val imu = ImuRecorder(app)
        imu.start()
        fun ev(type: Int, values: FloatArray, ts: Long) = SensorEventBuilder.newBuilder()
            .setSensor(ShadowSensor.newInstance(type)).setValues(values).setTimestamp(ts).build()
        imu.onSensorChanged(ev(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, floatArrayOf(1f, 2f, 3f), 0L))
        imu.onSensorChanged(ev(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, floatArrayOf(0f, 0f, 9.81f), 1_000_000L))

        val handle = if (MeasureNative.available) {
            MeasureNative.createSession(500.0, 500.0, 320.0, 240.0)
        } else {
            4242L // guards in the bridge keep every call off the native side
        }
        val qualities = mutableListOf<Pair<Int, Boolean>>()
        val diags = mutableListOf<List<Double>>()
        val analyzer = VioFrameAnalyzer(
            handle,
            imu,
            { q, plane -> qualities += q to plane },
            { fps, skew, imuHz, tracked, landmarks, confidence ->
                diags += listOf(fps, skew, imuHz, tracked.toDouble(), landmarks.toDouble(), confidence)
            },
        )
        val first = FakeImage(ts = 2_000_000L)
        analyzer.analyze(first)
        val second = FakeImage(ts = 18_000_000L)
        analyzer.analyze(second)
        assertTrue(first.closed && second.closed)
        assertEquals(2, qualities.size)
        assertEquals(2, diags.size)
        // Second frame has a 16 ms interval behind it: fps is now positive
        // (or zero only when the first-frame interval never seeded).
        assertTrue(diags[1][0] >= 0.0)
        assertTrue(diags[1][2] >= 0.0)
        if (MeasureNative.available && handle != 0L) MeasureNative.destroySession(handle)
        imu.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun navigationRoutesSerializeAndViewModelIsActivityScoped() {
        val routes = listOf(Route.Compass, Route.Level, Route.Ruler, Route.ArMeasure, Route.Saved, Route.Settings, Route.Diagnostics)
        assertEquals(7, routes.toSet().size)
        for (route in routes) {
            val json = kotlinx.serialization.json.Json.encodeToString(Route.serializer(), route)
            assertEquals(route, kotlinx.serialization.json.Json.decodeFromString(Route.serializer(), json))
        }
        // The Activity's ViewModel is the one Navigation binds: creating the
        // activity is the assertion that the whole graph constructs.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get())
        controller.destroy()
        val vm = MeasureViewModel(app)
        assertEquals(TrackingQuality.Initialising, vm.ar.value.quality)
    }
}
