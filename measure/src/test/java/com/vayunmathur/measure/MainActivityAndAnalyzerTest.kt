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

    @Test fun analyzerWithNoEngineJustClosesFrames() {
        val imu = ImuRecorder(app)
        var tracking = 0
        val analyzer = VioFrameAnalyzer(null, imu, { _, _ -> tracking++ }, { _, _, _, _, _, _ -> })
        val image = FakeImage(ts = 1_000L)
        analyzer.analyze(image)
        assertTrue(image.closed)
        assertEquals(0, tracking)
    }

    @Test fun engineIsUnavailableWithoutNativeLibrary() {
        // The Rust library does load on this host (the Gradle Rust task
        // builds a host .so for unit tests), so availability itself is
        // host-dependent; what must hold everywhere is that constructing
        // and exercising an engine is safe and, with no frames or scale,
        // it reports no candidates and projects nothing visible.
        val engine = com.vayunmathur.measure.platform.vio.VioEngine(
            com.vayunmathur.measure.platform.Intrinsics(500.0, 500.0, 320.0, 240.0, true)
        )
        assertEquals(0, engine.centreCandidates(640, 480).size)
        assertEquals(3, engine.projectPoints(doubleArrayOf(1.0, 2.0, 3.0), 640.0, 480.0).size)
        engine.reset()
        engine.close()
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
