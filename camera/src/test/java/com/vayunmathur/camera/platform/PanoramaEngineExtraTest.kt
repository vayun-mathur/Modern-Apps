package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PanoramaEngine: frame handoff ownership, sweep dot layouts, stitch guard. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanoramaEngineExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun engine() = PanoramaEngine(context)
    private fun bmp(w: Int = 16, h: Int = 12) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }

    @Test fun offerAndTakeFrameOwnership() {
        val e = engine()
        val a = bmp(); val b = bmp()
        e.offerFrame(a, 90)
        assertEquals(90, e.latestFrameRotation)
        e.offerFrame(b, 180) // superseded frame is recycled by the engine
        assertTrue(a.isRecycled)
        val (taken, rot) = e.takeFrame()
        assertTrue(taken === b); assertEquals(180, rot)
        val (none, _) = e.takeFrame()
        assertNull(none)
        b.recycle()
    }

    @Test fun latestFrameSetterRecyclesPrevious() {
        val e = engine()
        val a = bmp(); val b = bmp()
        e.latestFrame = a
        e.latestFrame = b
        assertTrue(a.isRecycled)
        e.latestFrame = null
        assertTrue(b.isRecycled)
    }

    @Test fun flatSweepLaysOutTwelveDots() {
        val e = engine()
        e.startSweep(fullSphere = false)
        assertTrue(e.isSweeping.value)
        val dots = e.guideDots.value
        assertEquals(12, dots.size)
        assertEquals(GuideDotState.CAPTURING, dots[0].state)
        assertTrue(dots.drop(1).all { it.state == GuideDotState.PENDING })
        assertEquals(30f, dots[1].targetAngle)
        assertEquals(330f, dots[11].targetAngle)
        // No analyzer frame was offered, so dot 0 must not be marked captured.
        assertEquals(0, e.frameCount.value)
        e.stopSweep()
        assertTrue(!e.isSweeping.value)
    }

    @Test fun sphereSweepLaysOutFortyDotsAcrossPitchRows() {
        val e = engine()
        e.startSweep(fullSphere = true)
        val dots = e.guideDots.value
        assertEquals(40, dots.size)
        assertEquals(listOf(0f, -30f, 30f, -60f, 60f), dots.map { it.targetPitch }.distinct())
        e.stopSweep()
    }

    @Test fun stitchWithTooFewFramesReturnsNull() = runBlocking {
        val e = engine()
        assertNull(e.stitch())
        assertTrue(!e.isStitching.value)
    }

    @Test fun wrapDegreesNormalizes() {
        val m = PanoramaEngine::class.java.getDeclaredMethod("wrapDegrees", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun w(d: Float) = m.invoke(engine(), d) as Float
        assertEquals(0f, w(360f)); assertEquals(0f, w(0f))
        assertEquals(-90f, w(270f)); assertEquals(90f, w(-270f))
        assertEquals(180f, w(180f)); assertEquals(-179f, w(181f))
        assertEquals(45f, w(765f))
    }

    @Test fun uprightAndCompressFrameHelpers() {
        val e = engine()
        val cls = PanoramaEngine::class.java
        val upright = cls.getDeclaredMethod("uprightFrame", Bitmap::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val src = bmp(20, 10)
        assertTrue(upright.invoke(e, src, 0) === src)
        assertTrue(upright.invoke(e, src, 360) === src)
        val rotated = upright.invoke(e, src, -90) as Bitmap
        assertEquals(10, rotated.width); assertEquals(20, rotated.height)
        val compress = cls.getDeclaredMethod("compressFrame", Bitmap::class.java, Bitmap::class.java)
            .apply { isAccessible = true }
        val jpeg = compress.invoke(e, rotated, rotated) as ByteArray?
        assertTrue(jpeg != null && jpeg.isNotEmpty())
        // JPEG magic bytes.
        assertEquals(0xFF, jpeg!![0].toInt() and 0xFF); assertEquals(0xD8, jpeg[1].toInt() and 0xFF)
    }
}
