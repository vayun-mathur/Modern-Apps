package com.vayunmathur.camera.platform

import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.google.zxing.qrcode.QRCodeWriter
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PhotoAnalyzer decoding a real QR code rendered into the Y plane. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrDecodeRealTest {

    @Test fun analyzerDecodesRenderedQr() {
        val size = 240
        val matrix = QRCodeWriter().encode("https://example.com/qr", com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val y = ByteArray(size * size)
        for (row in 0 until size) {
            for (col in 0 until size) {
                y[row * size + col] = if (matrix.get(col, row)) 0 else 0xFF.toByte()
            }
        }
        fun plane(bytes: ByteArray, stride: Int) = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getBuffer" -> ByteBuffer.wrap(bytes)
                "getRowStride" -> stride
                "getPixelStride" -> 1
                else -> null
            }
        } as ImageProxy.PlaneProxy
        val planes = arrayOf(plane(y, size), plane(ByteArray(size * size / 2), size), plane(ByteArray(size * size / 2), size))
        val info = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageInfo::class.java)) { _, m, _ ->
            when (m.name) { "getRotationDegrees" -> 0; "getTimestamp" -> 11L; else -> null }
        } as ImageInfo
        val closed = BooleanArray(1)
        val proxy = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageProxy::class.java)) { _, m, _ ->
            when (m.name) {
                "getPlanes" -> planes
                "getWidth" -> size
                "getHeight" -> size
                "getFormat" -> android.graphics.ImageFormat.YUV_420_888
                "getImageInfo" -> info
                "getCropRect" -> Rect(0, 0, size, size)
                "close" -> { closed[0] = true; null }
                else -> null
            }
        } as ImageProxy

        var decoded: String? = null
        val analyzer = PhotoAnalyzer(onLuminance = { }, onQrDetected = { decoded = it })
        PhotoAnalyzer::class.java.getDeclaredField("lastDecodeAtMs")
            .apply { isAccessible = true }.setLong(analyzer, -10_000L)
        analyzer.analyze(proxy)
        assertTrue(closed[0])
        assertEquals("https://example.com/qr", decoded)
    }
}
