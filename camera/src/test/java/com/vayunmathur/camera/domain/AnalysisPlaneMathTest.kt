package com.vayunmathur.camera.domain

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisPlaneMathTest {

    /** Builds a plane with poisoned (0xEE) padding; pixel (x,y) = (x*7 + y*13) % 251. */
    private fun paddedPlane(w: Int, h: Int, rowStride: Int, pixelStride: Int = 1): PlaneView {
        val bytes = ByteArray(rowStride * h) { 0xEE.toByte() }
        for (y in 0 until h) {
            for (x in 0 until w) {
                bytes[y * rowStride + x * pixelStride] = ((x * 7 + y * 13) % 251).toByte()
            }
        }
        return PlaneView(ByteBuffer.wrap(bytes), rowStride, pixelStride, w, h)
    }

    @Test fun flatPlaneMeanAndGrid() {
        val plane = PlaneView(ByteBuffer.wrap(ByteArray(64 * 48) { 100 }), 64, 1, 64, 48)
        val stats = LumaPlaneMath.stats(plane)!!
        assertEquals(100.0, stats.mean)
        assertEquals(64, stats.gridWidth)
        assertEquals(48, stats.gridHeight)
        assertTrue(stats.grid.all { it == 100f })
    }

    @Test fun paddedStridesAreNotReadAsPixels() {
        val plane = paddedPlane(37, 23, 48)
        var sum = 0L
        var count = 0L
        var n = 0
        while (n < 37 * 23) {
            sum += ((n % 37 * 7 + n / 37 * 13) % 251)
            count++
            n += LumaPlaneMath.LUMA_SAMPLE_STEP
        }
        assertEquals(sum.toDouble() / count, LumaPlaneMath.mean(plane))
        val (grid, gridWidth) = LumaPlaneMath.grid(plane)!!
        assertEquals(37, gridWidth)
        assertEquals(0f, grid[0])
        assertEquals(((36 * 7 + 22 * 13) % 251).toFloat(), grid.last())
    }

    @Test fun pixelStrideIsHonoured() {
        val plane = paddedPlane(16, 16, 64, pixelStride = 2)
        val stats = LumaPlaneMath.stats(plane)!!
        assertEquals(16, stats.gridWidth)
        assertEquals(((15 * 7 + 15 * 13) % 251).toFloat(), stats.grid.last())
    }

    @Test fun gridDownsamplesLargePlanes() {
        val plane = paddedPlane(640, 480, 640)
        val (grid, gridWidth) = LumaPlaneMath.grid(plane)!!
        // stride 6 x 5 -> 107 x 96 samples, both sides capped at ~96+.
        assertEquals((640 + 5) / 6, gridWidth)
        assertEquals(gridWidth * 96, grid.size)
    }

    @Test fun unreadablePlanesReturnNull() {
        assertNull(LumaPlaneMath.stats(PlaneView(ByteBuffer.wrap(ByteArray(4)), 0, 1, 64, 48)))
        assertNull(LumaPlaneMath.stats(PlaneView(ByteBuffer.wrap(ByteArray(4)), 64, 1, 0, 48)))
        assertNull(LumaPlaneMath.grid(ByteBuffer.wrap(ByteArray(10)), 64, 1, 64, 48))
    }

    @Test fun bufferPositionIsUntouched() {
        val buffer = ByteBuffer.wrap(ByteArray(64 * 48) { 7 })
        buffer.position(11)
        LumaPlaneMath.stats(PlaneView(buffer, 64, 1, 64, 48))
        assertEquals(11, buffer.position())
    }
}
