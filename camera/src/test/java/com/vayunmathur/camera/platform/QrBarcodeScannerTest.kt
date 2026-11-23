package com.vayunmathur.camera.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.vayunmathur.camera.domain.FrameCrop
import com.vayunmathur.camera.domain.PlaneView
import com.vayunmathur.camera.platform.camera2.AnalysisFrame
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [QrBarcodeScanner] through the Camera2 frame seam: synthetic QR and
 * 1D barcodes rendered into a luma plane must decode with text + format,
 * upright and quarter-turned, full-frame and cropped.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrBarcodeScannerTest {

    private class FakeFrame(
        val w: Int,
        val h: Int,
        val yBytes: ByteArray,
        override val rotationDegrees: Int = 0,
        override val cropRect: FrameCrop = FrameCrop.fullFrame(w, h),
        rowStride: Int = w,
    ) : AnalysisFrame {
        override val width get() = w
        override val height get() = h
        override val timestampNs: Long = 42L
        override val yPlane = PlaneView(ByteBuffer.wrap(yBytes), rowStride, 1, w, h)
        override val planes: List<PlaneView> = listOf(
            yPlane,
            PlaneView(ByteBuffer.wrap(ByteArray((w + 1) / 2 * ((h + 1) / 2)) { 128.toByte() }), (w + 1) / 2, 1, (w + 1) / 2, (h + 1) / 2),
            PlaneView(ByteBuffer.wrap(ByteArray((w + 1) / 2 * ((h + 1) / 2)) { 128.toByte() }), (w + 1) / 2, 1, (w + 1) / 2, (h + 1) / 2),
        )
        override fun close() {}
    }

    private data class Hit(val text: String, val format: BarcodeFormat)

    private fun scanner(into: MutableList<Hit>): QrBarcodeScanner =
        QrBarcodeScanner { text, format -> into.add(Hit(text, format)) }
            .apply { lastDecodeAtMs = -10_000L }

    /** Renders [matrix] centred on a white canvas, black modules at 0. */
    private fun renderToLuma(matrix: BitMatrix, canvasW: Int, canvasH: Int): ByteArray {
        val out = ByteArray(canvasW * canvasH) { 0xFF.toByte() }
        val ox = (canvasW - matrix.width) / 2
        val oy = (canvasH - matrix.height) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) out[(oy + y) * canvasW + (ox + x)] = 0
        }
        return out
    }

    private fun transpose(bytes: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(bytes.size)
        for (y in 0 until h) for (x in 0 until w) {
            out[x * h + (h - 1 - y)] = bytes[y * w + x]
        }
        return out
    }

    @Test
    fun decodesSyntheticQrWithFormat() {
        val matrix = QRCodeWriter().encode("https://example.com/qr-scan", BarcodeFormat.QR_CODE, 240, 240)
        val hits = mutableListOf<Hit>()
        scanner(hits).onAnalysisFrame(FakeFrame(280, 280, renderToLuma(matrix, 280, 280)))
        assertEquals(listOf(Hit("https://example.com/qr-scan", BarcodeFormat.QR_CODE)), hits)
    }

    @Test
    fun decodesSyntheticCode128() {
        val matrix = Code128Writer().encode("SCAN-12345", BarcodeFormat.CODE_128, 360, 90)
        val hits = mutableListOf<Hit>()
        scanner(hits).onAnalysisFrame(FakeFrame(440, 170, renderToLuma(matrix, 440, 170)))
        assertEquals(listOf(Hit("SCAN-12345", BarcodeFormat.CODE_128)), hits)
    }

    @Test
    fun decodesQuarterTurnedCode128ViaRotationMetadata() {
        val matrix = Code128Writer().encode("TURNED-678", BarcodeFormat.CODE_128, 360, 90)
        val upright = renderToLuma(matrix, 440, 170)
        val turned = transpose(upright, 440, 170)
        val hits = mutableListOf<Hit>()
        scanner(hits).onAnalysisFrame(FakeFrame(170, 440, turned, rotationDegrees = 90))
        assertEquals(listOf(Hit("TURNED-678", BarcodeFormat.CODE_128)), hits)
    }

    @Test
    fun decodesQrInsideCropRect() {
        val matrix = QRCodeWriter().encode("WIFI:T:WPA;S:HomeNet;P:s3cret;;", BarcodeFormat.QR_CODE, 200, 200)
        // Code occupies the centre 200x200 of a 320x320 white frame.
        val luma = renderToLuma(matrix, 320, 320)
        val hits = mutableListOf<Hit>()
        scanner(hits).onAnalysisFrame(
            FakeFrame(320, 320, luma, cropRect = FrameCrop(60, 60, 260, 260))
        )
        assertEquals(1, hits.size)
        assertTrue(hits[0].text.startsWith("WIFI:"))
        assertEquals(BarcodeFormat.QR_CODE, hits[0].format)
    }

    @Test
    fun blankFrameDecodesNothing() {
        val hits = mutableListOf<Hit>()
        scanner(hits).onAnalysisFrame(FakeFrame(160, 120, ByteArray(160 * 120) { 0xFF.toByte() }))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun cadenceDropsImmediateSecondFrame() {
        val matrix = QRCodeWriter().encode("https://example.com/cadence", BarcodeFormat.QR_CODE, 240, 240)
        val luma = renderToLuma(matrix, 280, 280)
        val hits = mutableListOf<Hit>()
        val scanner = scanner(hits)
        scanner.onAnalysisFrame(FakeFrame(280, 280, luma))
        // No rewind: the second frame lands inside QR_SCAN_INTERVAL_MS.
        scanner.onAnalysisFrame(FakeFrame(280, 280, luma))
        assertEquals(1, hits.size)
    }

    @Test
    fun stridedDirectBufferPlaneDecodesViaPackedCopy() {
        val matrix = QRCodeWriter().encode("https://example.com/strided", BarcodeFormat.QR_CODE, 240, 240)
        val tight = renderToLuma(matrix, 280, 280)
        // Re-pack with 16 bytes of row padding, as a HAL YUV plane would,
        // in a direct buffer so the zero-copy path is unavailable and the
        // packed-copy path has to carry the decode.
        val paddedStride = 280 + 16
        val padded = ByteArray(paddedStride * 280) { 0xFF.toByte() }
        for (row in 0 until 280) System.arraycopy(tight, row * 280, padded, row * paddedStride, 280)
        val direct = ByteBuffer.allocateDirect(padded.size).apply { put(padded) }
        val frame = object : AnalysisFrame {
            override val width = 280
            override val height = 280
            override val timestampNs = 42L
            override val rotationDegrees = 0
            override val cropRect = FrameCrop.fullFrame(280, 280)
            override val yPlane = PlaneView(direct, paddedStride, 1, 280, 280)
            override val planes = listOf(yPlane)
            override fun close() {}
        }
        val hits = mutableListOf<Hit>()
        scanner(hits).onAnalysisFrame(frame)
        assertEquals(listOf(Hit("https://example.com/strided", BarcodeFormat.QR_CODE)), hits)
    }
}
