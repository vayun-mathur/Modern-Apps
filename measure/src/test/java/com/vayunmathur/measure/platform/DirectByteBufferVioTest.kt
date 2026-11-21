package com.vayunmathur.measure.platform

import com.vayunmathur.measure.platform.vio.MeasureCvNative
import com.vayunmathur.measure.platform.vio.VioEngine
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The direct-ByteBuffer frame path (CameraX Y plane -> JNI
 * GetDirectBufferAddress -> retained Rust Gray buffer) must produce exactly
 * the ByteArray path's output on padded planes, and fall back safely for
 * heap buffers. Host-side: skipped when the native library is unavailable.
 */
class DirectByteBufferVioTest {

    private val w = 320
    private val h = 240
    private val pad = 17
    private val stride = w + pad

    private fun frame(shiftX: Int, shiftY: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = x - shiftX
                val sy = y - shiftY
                val v = (sin(sx * 0.31) * cos(sy * 0.23) + sin((sx + sy) * 0.13) +
                    cos(sx * 0.07 - sy * 0.09)) * 55.0 + 128.0
                out[y * w + x] = v.coerceIn(0.0, 255.0).toInt().toByte()
            }
        }
        return out
    }

    /** [tight] re-laid at [stride] with junk padding after every row. */
    private fun padded(tight: ByteArray): ByteArray {
        val out = ByteArray(stride * h) { 0xEE.toByte() }
        for (y in 0 until h) {
            System.arraycopy(tight, y * w, out, y * stride, w)
        }
        return out
    }

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }

    @Test
    fun directBufferMatchesByteArrayPathOnPaddedFrames() {
        if (!MeasureCvNative.available) return
        val f1 = padded(frame(0, 0))
        val f2 = padded(frame(4, 2))

        val arrayHandle = MeasureCvNative.createSession(300.0, 300.0, 160.0, 120.0)
        val directHandle = MeasureCvNative.createSession(300.0, 300.0, 160.0, 120.0)
        try {
            assertTrue(arrayHandle != 0L && directHandle != 0L)
            val a1 = assertNotNull(MeasureCvNative.processFrame(arrayHandle, f1, w, h, stride))
            val b1 = assertNotNull(MeasureCvNative.processFrame(directHandle, direct(f1), w, h, stride))
            assertEquals(a1.firstFrame, b1.firstFrame)
            assertEquals(a1.pointCount, b1.pointCount)
            assertTrue(b1.pointCount >= 20, "seeded ${b1.pointCount}")

            val a2 = assertNotNull(MeasureCvNative.processFrame(arrayHandle, f2, w, h, stride))
            val b2 = assertNotNull(MeasureCvNative.processFrame(directHandle, direct(f2), w, h, stride))
            assertEquals(a2.pairs, b2.pairs)
            assertEquals(a2.pointCount, b2.pointCount)
            assertEquals(a2.seeded, b2.seeded)
            assertEquals(a2.forwardOk, b2.forwardOk)
            assertEquals(a2.fbSurvivors, b2.fbSurvivors)
            assertEquals(a2.ransacInliers, b2.ransacInliers)
            assertEquals(a2.pose == null, b2.pose == null)
            val aPose = a2.pose
            val bPose = b2.pose
            if (aPose != null && bPose != null) {
                assertTrue(aPose.r.contentEquals(bPose.r))
                assertEquals(aPose.t, bPose.t)
            }
            assertTrue(b2.pairs.size >= 15, "direct path tracked ${b2.pairs.size}")
        } finally {
            MeasureCvNative.destroySession(arrayHandle)
            MeasureCvNative.destroySession(directHandle)
        }
    }

    @Test
    fun heapBufferFallsBackAndStillTracks() {
        if (!MeasureCvNative.available) return
        val handle = MeasureCvNative.createSession(300.0, 300.0, 160.0, 120.0)
        try {
            val heap = ByteBuffer.wrap(padded(frame(0, 0)))
            val first = assertNotNull(MeasureCvNative.processFrame(handle, heap, w, h, stride))
            assertTrue(first.firstFrame)
            assertTrue(first.pointCount >= 20)
        } finally {
            MeasureCvNative.destroySession(handle)
        }
    }

    @Test
    fun vioEngineByteBufferOverloadMatchesByteArrayOverload() {
        if (!MeasureCvNative.available) return
        val intrinsics = Intrinsics(300.0, 300.0, 160.0, 120.0, true)
        val viaArray = VioEngine(intrinsics, w, h)
        val viaBuffer = VioEngine(intrinsics, w, h)
        try {
            val f1 = padded(frame(0, 0))
            val f2 = padded(frame(4, 2))
            val a1 = viaArray.processFrame(f1, w, h, stride, 1_000_000L)
            val b1 = viaBuffer.processFrame(direct(f1), w, h, stride, 1_000_000L)
            assertEquals(a1, b1)
            val a2 = viaArray.processFrame(f2, w, h, stride, 34_000_000L)
            val b2 = viaBuffer.processFrame(direct(f2), w, h, stride, 34_000_000L)
            assertEquals(a2, b2)
            assertTrue(b2.trackedFeatures >= 15, "engine tracked ${b2.trackedFeatures}")
        } finally {
            viaArray.close()
            viaBuffer.close()
        }
    }
}
