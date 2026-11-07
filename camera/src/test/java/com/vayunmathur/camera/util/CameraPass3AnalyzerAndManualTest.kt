package com.vayunmathur.camera.util

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.qrcode.QRCodeWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/** Pass 3: manual-exposure state, PhotoAnalyzer luminance cadence and QR decode. */
@RunWith(AndroidJUnit4::class)
class CameraPass3AnalyzerAndManualTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun vm() = CameraViewModel(app)

    // ---- Manual controls ----

    @Test fun exposureAndIsoIndicesClamp() {
        val v = vm()
        assertTrue(v.isExposureAuto())
        v.setExposureTimeIndex(999)
        assertEquals(CameraViewModel.EXPOSURE_TIME_STOPS.lastIndex, v.exposureTimeIndexMutable.value)
        assertFalse(v.isExposureAuto())
        v.setExposureTimeIndex(-3)
        assertEquals(0, v.exposureTimeIndexMutable.value)
        assertTrue(v.isExposureAuto())

        v.isoStopsMutable.value = listOf(100, 200, 400)
        v.setManualIsoIndex(2)
        assertEquals(200, v.manualIso())
        assertFalse(v.isExposureAuto())
        v.setManualIsoIndex(99)
        assertEquals(3, v.manualIsoIndexMutable.value)
        assertEquals(400, v.manualIso())
        v.setManualIsoIndex(0)
        assertNull(v.manualIso())
        assertTrue(v.isExposureAuto())
        // No bound camera: applying and reading ranges are safe no-ops.
        v.applyManualControls()
        v.readManualControlRanges()
        assertNull(v.camera2ControlOrNull())
    }

    // ---- PhotoAnalyzer ----

    private class FakeFrame(
        val width: Int,
        val height: Int,
        val yBytes: ByteArray,
        val rotation: Int = 0,
    ) {
        var closed = false
        fun proxy(): ImageProxy {
            val planeHandler = InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getRowStride" -> width
                    "getPixelStride" -> 1
                    "getBuffer" -> ByteBuffer.wrap(yBytes.copyOf())
                    else -> null
                }
            }
            val plane = Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java), planeHandler)
                as ImageProxy.PlaneProxy
            val infoHandler = InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getTimestamp" -> 1234L
                    "getRotationDegrees" -> rotation
                    else -> null
                }
            }
            val info = Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(ImageInfo::class.java), infoHandler) as ImageInfo
            val handler = InvocationHandler { _, method, _ ->
                when (method.name) {
                    "getWidth" -> width
                    "getHeight" -> height
                    "getPlanes" -> arrayOf(plane)
                    "getImageInfo" -> info
                    "getFormat" -> android.graphics.ImageFormat.YUV_420_888
                    "close" -> { closed = true; null }
                    else -> null
                }
            }
            return Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(ImageProxy::class.java), handler) as ImageProxy
        }
    }

    @Test fun analyzerReportsAverageLuminanceAndCloses() {
        var luminance: Float? = null
        var qr: String? = null
        val analyzer = PhotoAnalyzer(onLuminance = { luminance = it }, onQrDetected = { qr = it })
        val frame = FakeFrame(64, 64, ByteArray(64 * 64) { 200.toByte() })
        analyzer.analyze(frame.proxy())
        assertEquals(200f, luminance)
        assertNull(qr)
        assertTrue(frame.closed)
    }

    @Test fun analyzerDarkFrameLuminance() {
        var luminance: Float? = null
        val analyzer = PhotoAnalyzer(onLuminance = { luminance = it }, onQrDetected = {})
        val frame = FakeFrame(32, 32, ByteArray(32 * 32) { 10.toByte() })
        analyzer.analyze(frame.proxy())
        assertEquals(10f, luminance)
        assertTrue(frame.closed)
    }

    @Test fun analyzerDecodesQrFromYPlane() {
        val size = 200
        val matrix = QRCodeWriter().encode("https://example.com/ff", com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val bytes = ByteArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if (matrix.get(x, y)) 0.toByte() else 255.toByte()
        }
        var qr: String? = null
        // Robolectric's elapsedRealtime starts at 0, and the analyzer caps
        // QR decodes to one per 400 ms: advance past the first window.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(
            1, java.util.concurrent.TimeUnit.SECONDS)
        val analyzer = PhotoAnalyzer(onLuminance = {}, onQrDetected = { qr = it })
        val frame = FakeFrame(size, size, bytes)
        analyzer.analyze(frame.proxy())
        assertEquals("https://example.com/ff", qr)
        assertTrue(frame.closed)
    }
}
