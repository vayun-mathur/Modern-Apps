package com.vayunmathur.camera.platform
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
/** PhotoAnalyzer Camera2 entry decoding a real QR rendered into the Y plane. */
@GraphicsMode(GraphicsMode.Mode.NATIVE) @RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class QrDecodeRealTest {

    class FakeFrame(
        val w: Int = 64, val h: Int = 48,
        val yBytes: ByteArray = ByteArray(64 * 48) { (it * 7 % 251).toByte() },
        override val timestampNs: Long = 42L,
        override val rotationDegrees: Int = 90,
    ) : com.vayunmathur.camera.platform.camera2.AnalysisFrame {
        var closed = false
        override val width get() = w; override val height get() = h
        override val cropRect = com.vayunmathur.camera.domain.FrameCrop.fullFrame(w, h)
        override val yPlane = com.vayunmathur.camera.domain.PlaneView(java.nio.ByteBuffer.wrap(yBytes), w, 1, w, h)
        override val planes: List<com.vayunmathur.camera.domain.PlaneView> = listOf(
            yPlane,
            com.vayunmathur.camera.domain.PlaneView(java.nio.ByteBuffer.wrap(ByteArray((w+1)/2*((h+1)/2)){128.toByte()}), (w+1)/2, 1, (w+1)/2, (h+1)/2),
            com.vayunmathur.camera.domain.PlaneView(java.nio.ByteBuffer.wrap(ByteArray((w+1)/2*((h+1)/2)){128.toByte()}), (w+1)/2, 1, (w+1)/2, (h+1)/2),
        )
        override fun close() { closed = true }
    }

    @Test fun decodesRenderedQrViaAnalysisFrame() {
        val size=240; val matrix=QRCodeWriter().encode("https://example.com/qr", com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val y=ByteArray(size*size); for(r in 0 until size) for(c in 0 until size) y[r*size+c]=if(matrix.get(c,r)) 0 else 0xFF.toByte()
        var decoded:String?=null; val a=PhotoAnalyzer(onLuminance={},onQrDetected={decoded=it})
        PhotoAnalyzer::class.java.getDeclaredField("lastDecodeAtMs").apply{isAccessible=true}.setLong(a,-10_000L)
        a.onAnalysisFrame(FakeFrame(size,size,y,11L,0)); assertEquals("https://example.com/qr",decoded)
    }
    @Test fun blankFrameDecodesNothing() {
        var decoded:String?=null; val a=PhotoAnalyzer(onLuminance={},onQrDetected={decoded=it})
        PhotoAnalyzer::class.java.getDeclaredField("lastDecodeAtMs").apply{isAccessible=true}.setLong(a,-10_000L)
        a.onAnalysisFrame(FakeFrame(64,48,ByteArray(64*48){0xFF.toByte()})); assertNull(decoded)
    }
}
