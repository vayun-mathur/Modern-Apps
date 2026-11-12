package com.vayunmathur.camera.domain

import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Known-frame checks for the Motion Photo ARGB → YUV 4:2:0 conversion: colour order, BT.601
 * values, row/pixel stride handling (padding bytes untouched), semi-planar aliasing and
 * bit-exactness against the previous per-pixel encoder loop.
 */
class ArgbToI420ConverterTest {

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val PAD: Byte = 0x5A

        /** BT.601 (Y, U, V) for the primaries above, as produced by the fixed-point weights. */
        val RED_YUV = intArrayOf(82, 90, 240)
        val GREEN_YUV = intArrayOf(144, 54, 34)
        val BLUE_YUV = intArrayOf(41, 240, 110)
        val WHITE_YUV = intArrayOf(235, 128, 128)
        val BLACK_YUV = intArrayOf(16, 128, 128)
    }

    private fun padded(size: Int) = ByteBuffer.allocate(size).also { buf ->
        repeat(size) { buf.put(it, PAD) }
    }

    private fun ByteBuffer.at(i: Int) = get(i).toInt() and 0xFF

    @Test
    fun solidColoursMapToBt601Values() {
        for ((argb, yuv) in listOf(RED to RED_YUV, GREEN to GREEN_YUV, BLUE to BLUE_YUV,
            WHITE to WHITE_YUV, BLACK to BLACK_YUV)) {
            val y = padded(4)
            val u = padded(1)
            val v = padded(1)
            ArgbToI420Converter(2, 2).convert(
                IntArray(4) { argb }, YuvPlane(y, 2, 1), YuvPlane(u, 1, 1), YuvPlane(v, 1, 1)
            )
            repeat(4) { assertEquals(yuv[0], y.at(it), "Y of ${Integer.toHexString(argb)}") }
            assertEquals(yuv[1], u.at(0), "U of ${Integer.toHexString(argb)}")
            assertEquals(yuv[2], v.at(0), "V of ${Integer.toHexString(argb)}")
        }
    }

    @Test
    fun planarWithRowPaddingKeepsOrderAndLeavesPaddingUntouched() {
        // 4x2: left 2x2 block red/green (top-left red), right block blue/white (top-left blue).
        val argb = intArrayOf(
            RED, GREEN, BLUE, WHITE,
            GREEN, RED, WHITE, BLUE,
        )
        val yStride = 6
        val cStride = 3
        val y = padded(yStride + 4)
        val u = padded(2)
        val v = padded(2)
        // Over-sized chroma buffers to check bytes past the last sample stay untouched too.
        val uBig = padded(cStride + 2)
        val vBig = padded(cStride + 2)
        ArgbToI420Converter(4, 2).convert(
            argb, YuvPlane(y, yStride, 1), YuvPlane(u, cStride, 1), YuvPlane(v, cStride, 1)
        )
        ArgbToI420Converter(4, 2).convert(argb, YuvPlane(padded(yStride + 4), yStride, 1),
            YuvPlane(uBig, cStride, 1), YuvPlane(vBig, cStride, 1))

        val expectedY = intArrayOf(82, 144, 41, 235, -1, -1, 144, 82, 235, 41)
        expectedY.forEachIndexed { i, e ->
            if (e < 0) assertEquals(PAD, y.get(i), "row padding at $i") else assertEquals(e, y.at(i), "Y[$i]")
        }
        // Chroma comes from each block's top-left pixel: red then blue.
        assertEquals(RED_YUV[1], u.at(0))
        assertEquals(BLUE_YUV[1], u.at(1))
        assertEquals(RED_YUV[2], v.at(0))
        assertEquals(BLUE_YUV[2], v.at(1))
        assertEquals(PAD, uBig.get(2))
        assertEquals(PAD, vBig.get(cStride + 1))
    }

    @Test
    fun semiPlanarAliasedPlanesInterleaveUv() {
        // NV12: one buffer, U at even offsets and V at odd offsets (V plane = U plane + 1).
        val argb = intArrayOf(
            RED, RED, BLUE, BLUE,
            RED, RED, BLUE, BLUE,
            GREEN, GREEN, WHITE, WHITE,
            GREEN, GREEN, WHITE, WHITE,
        )
        val rowStride = 6 // 4 interleaved bytes + 2 padding
        val uv = padded(rowStride + 4)
        val u = uv.duplicate()
        val v = uv.duplicate().apply { position(1) }.slice()
        val y = padded(16)
        ArgbToI420Converter(4, 4).convert(argb, YuvPlane(y, 4, 1), YuvPlane(u, rowStride, 2), YuvPlane(v, rowStride, 2))

        val expected = intArrayOf(
            RED_YUV[1], RED_YUV[2], BLUE_YUV[1], BLUE_YUV[2], -1, -1,
            GREEN_YUV[1], GREEN_YUV[2], WHITE_YUV[1], WHITE_YUV[2],
        )
        expected.forEachIndexed { i, e ->
            if (e < 0) assertEquals(PAD, uv.get(i), "row padding at $i") else assertEquals(e, uv.at(i), "UV[$i]")
        }
    }

    @Test
    fun oddWidthRoundsChromaUp() {
        val argb = intArrayOf(
            RED, GREEN, BLUE,
            WHITE, BLACK, WHITE,
            BLUE, GREEN, RED,
        )
        val y = padded(9)
        val u = padded(4)
        val v = padded(4)
        ArgbToI420Converter(3, 3).convert(argb, YuvPlane(y, 3, 1), YuvPlane(u, 2, 1), YuvPlane(v, 2, 1))
        assertEquals(listOf(RED_YUV[1], BLUE_YUV[1], BLUE_YUV[1], RED_YUV[1]), (0 until 4).map { u.at(it) })
        assertEquals(listOf(RED_YUV[2], BLUE_YUV[2], BLUE_YUV[2], RED_YUV[2]), (0 until 4).map { v.at(it) })
        assertEquals(BLACK_YUV[0], y.at(4))
    }

    @Test
    fun matchesPreviousPerPixelEncoderBitForBit() {
        val w = 10
        val h = 6
        val rnd = Random(914)
        val argb = IntArray(w * h) { 0xFF000000.toInt() or rnd.nextInt(0x1000000) }
        for (pixelStride in listOf(1, 2)) {
            val yStride = 16
            val cStride = 12
            val actual = Triple(padded(yStride * h), padded(cStride * h / 2), padded(cStride * h / 2))
            val expected = Triple(padded(yStride * h), padded(cStride * h / 2), padded(cStride * h / 2))
            ArgbToI420Converter(w, h).convert(
                argb, YuvPlane(actual.first, yStride, 1),
                YuvPlane(actual.second, cStride, pixelStride), YuvPlane(actual.third, cStride, pixelStride)
            )
            referencePerPixel(
                argb, w, h, expected.first, yStride, expected.second, expected.third, cStride, pixelStride
            )
            assertContentEquals(expected.first.array(), actual.first.array(), "Y, pixelStride=$pixelStride")
            assertContentEquals(expected.second.array(), actual.second.array(), "U, pixelStride=$pixelStride")
            assertContentEquals(expected.third.array(), actual.third.array(), "V, pixelStride=$pixelStride")
        }
    }

    @Test
    fun planePositionIsNotDisturbed() {
        val y = padded(4).apply { position(3) }
        val u = padded(1)
        val v = padded(1)
        ArgbToI420Converter(2, 2).convert(
            IntArray(4) { WHITE }, YuvPlane(y, 2, 1), YuvPlane(u, 1, 1), YuvPlane(v, 1, 1)
        )
        assertEquals(3, y.position())
        assertEquals(WHITE_YUV[0], y.at(0))
    }

    @Test
    fun rejectsShortPixelArray() {
        assertFailsWith<IllegalArgumentException> {
            ArgbToI420Converter(2, 2).convert(IntArray(3), YuvPlane(padded(4), 2, 1),
                YuvPlane(padded(1), 1, 1), YuvPlane(padded(1), 1, 1))
        }
    }

    /** The pre-#914 MotionPhotoEncoder.fillI420 loop, verbatim apart from the Image plumbing. */
    @Suppress("LongParameterList")
    private fun referencePerPixel(
        argb: IntArray, width: Int, height: Int,
        yBuf: ByteBuffer, yRowStride: Int,
        uBuf: ByteBuffer, vBuf: ByteBuffer, cRowStride: Int, cPixStride: Int,
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(y * yRowStride + x, yy.coerceIn(0, 255).toByte())
                if (y and 1 == 0 && x and 1 == 0) {
                    val u = ((-38 * r + -74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r + -94 * g + -18 * b + 128) shr 8) + 128
                    val cx = x / 2
                    val cy = y / 2
                    uBuf.put(cy * cRowStride + cx * cPixStride, u.coerceIn(0, 255).toByte())
                    vBuf.put(cy * cRowStride + cx * cPixStride, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
