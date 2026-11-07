package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/** Pass 3: bitmap crop/rotate/color processing and EXIF stamping on real bitmaps. */
@RunWith(AndroidJUnit4::class)
class CameraPass3ProcessingTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun vm() = CameraViewModel(app)

    private fun bitmap(w: Int, h: Int, color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun cropFullFrameReturnsSameBitmap() {
        val v = vm()
        val src = bitmap(100, 80)
        assertSame(src, v.cropToRect(src, Rect(0, 0, 100, 80)))
        assertSame(src, v.cropToRect(src, Rect(0, 0, 0, 0)))
        assertFalse(src.isRecycled)
    }

    @Test fun cropPartialClampsAndRecyclesSource() {
        val v = vm()
        val src = bitmap(100, 80)
        val cropped = v.cropToRect(src, Rect(10, 20, 60, 60))
        assertEquals(50, cropped.width)
        assertEquals(40, cropped.height)
        assertTrue(src.isRecycled)
        // Out-of-bounds rect is clamped to the frame.
        val src2 = bitmap(50, 50)
        val clamped = v.cropToRect(src2, Rect(-10, -10, 500, 500))
        assertEquals(50, clamped.width)
        assertEquals(50, clamped.height)
    }

    @Test fun uprightCopyRotatesAndScales() {
        val identity = uprightCopy(bitmap(40, 20), 0, 0)
        assertEquals(40, identity.width)
        val src = bitmap(40, 20)
        val copy = uprightCopy(src, 0, 0)
        assertNotSame(src, copy)
        val rotated = uprightCopy(bitmap(40, 20), 90, 0)
        assertEquals(20, rotated.width)
        assertEquals(40, rotated.height)
        val scaled = uprightCopy(bitmap(200, 100), 0, 50)
        assertEquals(50, scaled.width)
        assertEquals(25, scaled.height)
        // maxSide larger than the frame never upscales.
        val unscaled = uprightCopy(bitmap(30, 10), 0, 500)
        assertEquals(30, unscaled.width)
    }

    @Test fun colorAdjustmentsReturnNewBitmapAndRecycleSource() {
        val src = bitmap(16, 16, Color.BLUE)
        val out = applyColorAdjustments(src, warmth = 0.5f, shadows = 0.2f, mirror = false)
        assertEquals(16, out.width)
        assertEquals(16, out.height)
        assertTrue(src.isRecycled)
        val src2 = bitmap(16, 16, Color.GREEN)
        val mirrored = applyColorAdjustments(src2, warmth = -0.5f, shadows = -0.2f, mirror = true)
        assertEquals(16, mirrored.width)
        assertTrue(src2.isRecycled)
        val neutral = applyColorAdjustments(bitmap(8, 8), warmth = 0f, shadows = 0f, mirror = false)
        assertEquals(8, neutral.width)
    }

    private fun jpegBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap(24, 16, Color.YELLOW).compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    @Test fun stampStillBytesWritesOrientation() {
        val v = vm()
        val jpeg = jpegBytes()
        assertTrue(jpeg.isNotEmpty())
        val stamped = v.stampStillBytes(jpeg, rotationDegrees = 90)
        val orientation = ExifInterface(ByteArrayInputStream(stamped))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, orientation)
    }

    @Test fun stampStillBytesMirroredInvertsRotation() {
        val v = vm()
        val stamped = v.stampStillBytes(jpegBytes(), rotationDegrees = 90, mirrored = true)
        val orientation = ExifInterface(ByteArrayInputStream(stamped))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, orientation)
        val normal = v.stampStillBytes(jpegBytes(), rotationDegrees = 0)
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface(ByteArrayInputStream(normal))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
        val rotated180 = v.stampStillBytes(jpegBytes(), rotationDegrees = 180)
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface(ByteArrayInputStream(rotated180))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
    }

    @Test fun stampStillBytesOnGarbageReturnsInput() {
        val v = vm()
        val garbage = byteArrayOf(1, 2, 3, 4)
        assertEquals(garbage.toList(), v.stampStillBytes(garbage, rotationDegrees = 90).toList())
    }
}
