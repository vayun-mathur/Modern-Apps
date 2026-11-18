package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Capture-processing bitmap helpers: crop, upright copy, colour bake. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureProcessingMathTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun bitmap(w: Int, h: Int, color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun cropFullFrameReturnsSameBitmap() {
        val src = bitmap(20, 10)
        val out = with(vm()) { cropToRect(src, Rect(0, 0, 20, 10)) }
        assertTrue(out === src)
        assertTrue(!src.isRecycled)
    }

    @Test fun cropSubRectCropsAndRecyclesSource() {
        val src = bitmap(20, 10)
        val out = with(vm()) { cropToRect(src, Rect(5, 2, 15, 8)) }
        assertEquals(10, out.width); assertEquals(6, out.height)
        assertTrue(src.isRecycled)
    }

    @Test fun cropClampsOutOfBoundsRect() {
        val src = bitmap(20, 10)
        val out = with(vm()) { cropToRect(src, Rect(-5, -5, 100, 100)) }
        assertEquals(20, out.width); assertEquals(10, out.height)
    }

    @Test fun uprightCopyIdentityStillReturnsDistinctBitmap() {
        val src = bitmap(16, 16)
        val out = uprightCopy(src, 0, 0)
        assertTrue(out !== src)
        assertEquals(16, out.width)
    }

    @Test fun uprightCopyRotatesAndScales() {
        val src = bitmap(100, 50)
        val rotated = uprightCopy(src, 90, 0)
        assertEquals(50, rotated.width); assertEquals(100, rotated.height)
        val small = uprightCopy(src, 0, 25)
        assertEquals(25, small.width); assertEquals(13, small.height) // 50*0.25 rounds to 13 via createBitmap matrix
        val noUpscale = uprightCopy(src, 0, 500)
        assertEquals(100, noUpscale.width)
    }

    @Test fun applyColorAdjustmentsRecyclesAndPreservesSize() {
        val src = bitmap(12, 8, Color.rgb(100, 100, 100))
        val neutral = applyColorAdjustments(src, 0f, 0f, mirror = false)
        assertEquals(12, neutral.width); assertEquals(8, neutral.height)
        assertTrue(src.isRecycled)
        val px = neutral.getPixel(2, 2)
        assertEquals(100, Color.red(px)); assertEquals(100, Color.green(px))
        val warmSrc = bitmap(12, 8, Color.rgb(100, 100, 100))
        val warm = applyColorAdjustments(warmSrc, 1f, 0f, mirror = true)
        // Warmth tilts red up and blue down relative to neutral grey.
        assertTrue(Color.red(warm.getPixel(2, 2)) > Color.blue(warm.getPixel(2, 2)))
    }

    @Test fun orientationStringMapping() {
        val m = Class.forName("com.vayunmathur.camera.platform.CameraCaptureProcessingKt")
            .getDeclaredMethod("orientationString", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun o(deg: Int, mir: Boolean) = m.invoke(null, deg, mir) as String
        // ExifInterface orientation constants as strings; mirrored flips horizontally.
        assertEquals(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL.toString(), o(0, false))
        assertEquals(androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString(), o(90, false))
        assertEquals(androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180.toString(), o(180, false))
        assertEquals(androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270.toString(), o(270, false))
        // Mirrored pixels invert the rotation (FLIP∘ROTATE(θ)∘FLIP = ROTATE(−θ)).
        assertEquals(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL.toString(), o(0, true))
        assertEquals(androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270.toString(), o(90, true))
        assertEquals(androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString(), o(270, true))
        assertEquals(androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180.toString(), o(180, true))
        assertNotNull(o(45, false)) // non-right-angle falls back to a defined orientation
    }
}
