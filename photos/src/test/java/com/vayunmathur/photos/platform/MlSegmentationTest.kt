package com.vayunmathur.photos.platform

import android.graphics.Bitmap
import com.vayunmathur.library.ml.SegmentationMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The segmenter model downscales its input to <=320 anyway, so [runSegmentation]
 * must hand it the 512-capped copy it already computes for the mask grid —
 * not the full-size editor bitmap (which the native path first materialises
 * whole, ~12.6 MB x2 at editor sizes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MlSegmentationTest {

    @Test
    fun segmentationInputSizeCapsTheLongestSide() {
        assertEquals(512 to 256, segmentationInputSize(2048, 1024))
        assertEquals(256 to 512, segmentationInputSize(1024, 2048))
        assertEquals(512 to 512, segmentationInputSize(512, 512))
        assertEquals(320 to 200, segmentationInputSize(320, 200))
        assertEquals(1 to 1, segmentationInputSize(1, 1))
    }

    @Test
    fun segmenterReceivesTheCappedBitmapNotTheFullSizeOriginal() {
        val original = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        var handedToSegmenter: Bitmap? = null
        var handedSize: Pair<Int, Int>? = null
        val selection = runSegmentation(original) { input ->
            handedToSegmenter = input
            handedSize = input.width to input.height
            // Model-resolution mask (the real model returns <=320 on a side).
            SegmentationMask(4, 2, floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f))
        }
        val capped = assertNotNull(handedToSegmenter)
        assertEquals(512 to 256, handedSize)
        assertTrue(capped !== original, "segmenter must not receive the full-size bitmap")
        // The capped copy is recycled after the (synchronous) segment call;
        // the caller's original is untouched.
        assertTrue(capped.isRecycled)
        assertTrue(!original.isRecycled)
        // The mask is still upsampled onto the capped grid, as before.
        val sel = assertNotNull(selection)
        assertEquals(512, sel.width)
        assertEquals(256, sel.height)
        original.recycle()
    }

    @Test
    fun smallBitmapsAreHandedOverUnscaled() {
        val original = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        var handedToSegmenter: Bitmap? = null
        val selection = runSegmentation(original) { input ->
            handedToSegmenter = input
            SegmentationMask(2, 2, floatArrayOf(0f, 1f, 0f, 1f))
        }
        assertSame(original, handedToSegmenter)
        assertTrue(!original.isRecycled, "the caller's bitmap must not be recycled")
        val sel = assertNotNull(selection)
        assertEquals(320, sel.width)
        assertEquals(200, sel.height)
        original.recycle()
    }

    @Test
    fun downscaleReturnsTheSameBitmapWhenItAlreadyFits() {
        val original = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        assertSame(original, downscaleForSegmentation(original))
        original.recycle()
    }
}
