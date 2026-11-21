package com.vayunmathur.pdf.platform

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Zero-copy raw-raster decode: the ByteBuffer (wire-slice) path must produce
 * exactly the pixels the old ByteArray -> IntArray per-pixel loop produced,
 * and the JPEG arm must keep its subsampling on the new entry point.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SafePdfImagesZeroCopyTest {

    // RGBA8888, 3x2: opaque primaries + arbitrary colours + one translucent.
    private val rgba = intArrayOf(
        255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255,
        10, 20, 30, 255, 200, 150, 100, 255, 12, 34, 56, 128,
    ).map { it.toByte() }.toByteArray()

    /** The same pixels as [rgba], in format 2's ARGB-word (B,G,R,A) byte order. */
    private val argbWords: ByteArray = ByteArray(rgba.size).also { out ->
        for (p in rgba.indices step 4) {
            out[p] = rgba[p + 2]; out[p + 1] = rgba[p + 1]
            out[p + 2] = rgba[p]; out[p + 3] = rgba[p + 3]
        }
    }

    private fun pixels(bmp: Bitmap): List<Int> =
        (0 until bmp.height).flatMap { y -> (0 until bmp.width).map { x -> bmp.getPixel(x, y) } }

    @Test fun rawBufferPathMatchesByteArrayPathPixelForPixel() {
        // Legacy format 0 (RGBA, per-pixel on the ByteArray entry point) is
        // the reference; format 2 over a buffer slice must match it exactly.
        val viaArray = assertNotNull(SafePdfImages.decodeBitmap(3, 2, 0, rgba))
        val buffer = ByteBuffer.wrap(argbWords)
        val viaBuffer = assertNotNull(
            SafePdfImages.decodeBitmap(3, 2, SafePdfImages.FORMAT_ARGB_WORDS, buffer)
        )
        assertEquals(pixels(viaArray), pixels(viaBuffer))
        // Absolute expectations for the opaque pixels (RGBA byte order on the wire).
        assertEquals(Color.argb(255, 255, 0, 0), viaBuffer.getPixel(0, 0))
        assertEquals(Color.argb(255, 0, 255, 0), viaBuffer.getPixel(1, 0))
        assertEquals(Color.argb(255, 0, 0, 255), viaBuffer.getPixel(2, 0))
        assertEquals(Color.argb(255, 10, 20, 30), viaBuffer.getPixel(0, 1))
        assertEquals(Color.argb(255, 200, 150, 100), viaBuffer.getPixel(1, 1))
        assertEquals(0, buffer.position(), "decode must not consume the caller's buffer")
    }

    @Test fun rawBufferPathReadsFromTheBuffersPosition() {
        val padded = ByteArray(1) { 99 } + argbWords
        val buffer = ByteBuffer.wrap(padded)
        buffer.position(1)
        val bmp = assertNotNull(
            SafePdfImages.decodeBitmap(3, 2, SafePdfImages.FORMAT_ARGB_WORDS, buffer)
        )
        assertEquals(Color.argb(255, 255, 0, 0), bmp.getPixel(0, 0))
        assertEquals(1, buffer.position())
    }

    @Test fun rawViaWireParserMatchesExpectedPixels() {
        val page = SafePdfParser.parse(
            WireWriter().image(w = 3, h = 2, format = 0, data = rgba).build()
        )
        val bmp = assertNotNull(assertNotNull(page.primitives.single() as? PdfPrimitive.Image).bitmap)
        assertEquals(Color.argb(255, 255, 0, 0), bmp.getPixel(0, 0))
        assertEquals(Color.argb(255, 0, 0, 255), bmp.getPixel(2, 0))
        assertEquals(Color.argb(255, 200, 150, 100), bmp.getPixel(1, 1))
    }

    @Test fun jpegBufferPathMatchesByteArrayPathAndKeepsSubsampling() {
        val src = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 8) {
            src.setPixel(x, y, Color.argb(255, x * 30, y * 30, (x + y) * 15))
        }
        val out = ByteArrayOutputStream()
        assertTrue(src.compress(Bitmap.CompressFormat.JPEG, 100, out))
        val jpeg = out.toByteArray()

        val viaArray = assertNotNull(SafePdfImages.decodeBitmap(8, 8, 1, jpeg))
        val viaBuffer = assertNotNull(SafePdfImages.decodeBitmap(8, 8, 1, ByteBuffer.wrap(jpeg)))
        assertEquals(viaArray.width, viaBuffer.width)
        assertEquals(pixels(viaArray), pixels(viaBuffer))

        // Same declared dimensions as a 20 MP photo: the JPEG arm subsamples
        // by sampleSizeFor instead of decoding full size, on both entry points.
        val sampled = assertNotNull(SafePdfImages.decodeBitmap(5184, 3888, 1, ByteBuffer.wrap(jpeg)))
        assertTrue(sampled.width < 8, "expected subsampled JPEG, got ${sampled.width}x${sampled.height}")
    }
}
