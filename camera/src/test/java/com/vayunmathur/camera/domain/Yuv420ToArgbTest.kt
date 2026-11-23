package com.vayunmathur.camera.domain

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Yuv420ToArgbTest {

    private fun plane(bytes: ByteArray, rowStride: Int, pixelStride: Int, w: Int, h: Int) =
        PlaneView(ByteBuffer.wrap(bytes), rowStride, pixelStride, w, h)

    /** One-colour frame: constant Y plus one chroma sample per 2x2 block. */
    private fun solidFrame(width: Int, height: Int, y: Int, u: Int, v: Int): List<PlaneView> {
        val yPlane = plane(ByteArray(width * height) { y.toByte() }, width, 1, width, height)
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val uPlane = plane(ByteArray(cw * ch) { u.toByte() }, cw, 1, width, height)
        val vPlane = plane(ByteArray(cw * ch) { v.toByte() }, cw, 1, width, height)
        return listOf(yPlane, uPlane, vPlane)
    }

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF

    @Test fun blackAndWhiteEndpoints() {
        val black = Yuv420ToArgb.convert(solidFrame(2, 2, y = 16, u = 128, v = 128), 2, 2)!!
        assertTrue(black.all { it == 0xFF000000.toInt() })
        val white = Yuv420ToArgb.convert(solidFrame(2, 2, y = 235, u = 128, v = 128), 2, 2)!!
        assertTrue(white.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test fun neutralGreyIsNeutral() {
        val grey = Yuv420ToArgb.convert(solidFrame(4, 2, y = 128, u = 128, v = 128), 4, 2)!!.first()
        assertEquals(red(grey), green(grey))
        assertEquals(green(grey), blue(grey))
        assertTrue(red(grey) in 128..134, "mid grey should land near 130, got ${red(grey)}")
    }

    @Test fun chromaMovesColourInTheRightDirection() {
        // High V pushes red, high U pushes blue (BT.601 limited range).
        val redish = Yuv420ToArgb.convert(solidFrame(2, 2, y = 128, u = 128, v = 255), 2, 2)!!.first()
        assertTrue(red(redish) > green(redish) && red(redish) > blue(redish))
        val blueish = Yuv420ToArgb.convert(solidFrame(2, 2, y = 128, u = 255, v = 128), 2, 2)!!.first()
        assertTrue(blue(blueish) > red(blueish) && blue(blueish) > green(blueish))
    }

    @Test fun rowPaddingIsNotMistakenForPixels() {
        // 2-wide frame in 4-wide rows: bytes beyond column 1 are padding.
        val paddedY = plane(
            ByteArray(8) { if (it % 4 < 2) 16.toByte() else 99.toByte() }, rowStride = 4, pixelStride = 1, w = 2, h = 2,
        )
        val chroma = plane(ByteArray(1) { 128.toByte() }, rowStride = 1, pixelStride = 1, w = 2, h = 2)
        val out = Yuv420ToArgb.convert(listOf(paddedY, chroma, chroma), 2, 2)!!
        assertTrue(out.all { it == 0xFF000000.toInt() }, "padding must not leak into pixels")
    }

    @Test fun semiPlanarChromaPixelStrideIsHonoured() {
        // Chroma stored interleaved (pixelStride 2): sample 0 at byte 0, junk at byte 1.
        val yPlane = plane(ByteArray(4) { 16 }, rowStride = 2, pixelStride = 1, w = 2, h = 2)
        val uPlane = plane(byteArrayOf(128.toByte(), 0), rowStride = 2, pixelStride = 2, w = 2, h = 2)
        val vPlane = plane(byteArrayOf(128.toByte(), 0), rowStride = 2, pixelStride = 2, w = 2, h = 2)
        val out = Yuv420ToArgb.convert(listOf(yPlane, uPlane, vPlane), 2, 2)!!
        assertTrue(out.all { it == 0xFF000000.toInt() })
    }

    @Test fun invalidPlanesReturnNull() {
        assertNull(Yuv420ToArgb.convert(emptyList(), 2, 2))
        assertNull(Yuv420ToArgb.convert(solidFrame(2, 2, 16, 128, 128), 0, 2))
        val shortY = plane(ByteArray(2), rowStride = 2, pixelStride = 1, w = 2, h = 2)
        val chroma = plane(ByteArray(1) { 128.toByte() }, rowStride = 1, pixelStride = 1, w = 2, h = 2)
        assertNull(Yuv420ToArgb.convert(listOf(shortY, chroma, chroma), 2, 2))
    }
}
