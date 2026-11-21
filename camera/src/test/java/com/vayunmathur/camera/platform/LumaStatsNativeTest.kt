package com.vayunmathur.camera.platform

import com.vayunmathur.camera.domain.LumaPlaneMath
import com.vayunmathur.camera.domain.PlaneView
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin vs native luma-stats parity on synthetic Y planes (the
 * StillEncoderNativeTest pattern: real JNI bridge against the host .so,
 * skipped when the host library is not built in this environment).
 */
class LumaStatsNativeTest {

    /** Direct plane, padding poisoned with 0xEE; pixel (x,y) = (x*7 + y*13) % 251. */
    private fun directPlane(w: Int, h: Int, rowStride: Int, pixelStride: Int = 1): PlaneView {
        val buffer = ByteBuffer.allocateDirect(rowStride * h)
        for (i in 0 until rowStride * h) buffer.put(i, 0xEE.toByte())
        for (y in 0 until h) {
            for (x in 0 until w) {
                buffer.put(y * rowStride + x * pixelStride, ((x * 7 + y * 13) % 251).toByte())
            }
        }
        return PlaneView(buffer, rowStride, pixelStride, w, h)
    }

    private fun assertParity(plane: PlaneView) {
        val kotlin = LumaPlaneMath.stats(plane)
        assertNotNull(kotlin)
        // The unified entry point must agree whichever half computed it.
        val unified = StitchNative.lumaStats(plane)
        assertNotNull(unified)
        assertEquals(kotlin.gridWidth, unified.gridWidth)
        assertEquals(kotlin.gridHeight, unified.gridHeight)
        assertTrue(kotlin.grid.contentEquals(unified.grid), "grid values differ")
        assertTrue(abs(kotlin.mean - unified.mean) < 0.01, "mean ${kotlin.mean} vs ${unified.mean}")

        if (!StitchNative.isAvailable || !plane.buffer.isDirect) return
        val native = StitchNative.lumaStatsFromYPlane(
            plane.buffer, plane.width, plane.height, plane.rowStride, plane.pixelStride,
        )
        assertNotNull(native, "native returned null for a direct plane")
        val decoded = decodeLumaStats(native)
        assertNotNull(decoded)
        assertEquals(kotlin.gridWidth, decoded.gridWidth)
        assertEquals(kotlin.gridHeight, decoded.gridHeight)
        assertTrue(kotlin.grid.contentEquals(decoded.grid), "native grid differs from Kotlin")
        // Mean travels as float32 in the native encoding.
        assertTrue(abs(kotlin.mean - decoded.mean) < 0.01, "native mean ${decoded.mean} vs ${kotlin.mean}")
    }

    @Test fun parityOnPaddedDirectPlane() {
        assertParity(directPlane(37, 23, 48))
    }

    @Test fun parityOnTightlyPackedPlane() {
        assertParity(directPlane(64, 48, 64))
    }

    @Test fun parityWithPixelStride() {
        assertParity(directPlane(16, 16, 64, pixelStride = 2))
    }

    @Test fun parityOnGridDownsampledPlane() {
        assertParity(directPlane(640, 480, 640))
    }

    @Test fun heapBufferUsesKotlinFallbackWithSameResult() {
        val bytes = ByteArray(64 * 48) { (it % 251).toByte() }
        val heap = PlaneView(ByteBuffer.wrap(bytes), 64, 1, 64, 48)
        val viaUnified = StitchNative.lumaStats(heap)
        val viaKotlin = LumaPlaneMath.stats(heap)
        assertNotNull(viaUnified)
        assertEquals(viaKotlin, viaUnified)
        if (StitchNative.isAvailable) {
            // A heap buffer has no direct address: native must refuse, not crash.
            assertNull(
                StitchNative.lumaStatsFromYPlane(heap.buffer, 64, 48, 64, 1),
            )
        }
    }

    @Test fun decodeRejectsMalformedEncodings() {
        assertNull(decodeLumaStats(null))
        assertNull(decodeLumaStats(floatArrayOf(1f, 2f)))
        assertNull(decodeLumaStats(floatArrayOf(1f, 0f, 2f)))
        assertNull(decodeLumaStats(floatArrayOf(1f, 2f, 2f, 9f)))
        assertNotNull(decodeLumaStats(floatArrayOf(5f, 2f, 2f, 1f, 2f, 3f, 4f)))
    }
}
