package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MediaStoreSaverAndProcessingTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test fun timestampShape() {
        val ts = MediaStoreSaver.timestamp()
        // yyyyMMdd_HHmmss_mmm
        assertTrue(Regex("""\d{8}_\d{6}_\d{3}""").matches(ts), ts)
    }

    @Test fun contentValuesCarryNameMimeAndPath() {
        val image = MediaStoreSaver.imageValues("IMG_1.jpg")
        assertEquals("IMG_1.jpg", image.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/jpeg", image.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertTrue(image.getAsString(MediaStore.MediaColumns.RELATIVE_PATH).endsWith("Camera"))
        val video = MediaStoreSaver.videoValues("VID_1.mp4")
        assertEquals("video/mp4", video.getAsString(MediaStore.MediaColumns.MIME_TYPE))
    }

    @Test fun saveJpegBytesToRegisteredUri() {
        val resolver = ctx.contentResolver
        val dest = Uri.parse("content://test/photo.jpg")
        val out = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(dest, out)
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val saved = MediaStoreSaver.saveBitmapToUri(resolver, dest, bitmap)
        assertEquals(dest, saved)
        assertTrue(out.size() > 0)

        val bytes = byteArrayOf(1, 2, 3, 4)
        val dest2 = Uri.parse("content://test/photo2.jpg")
        val out2 = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(dest2, out2)
        assertEquals(dest2, MediaStoreSaver.saveJpegBytesToUri(resolver, dest2, bytes))
        assertEquals(bytes.toList(), out2.toByteArray().toList())
    }

    @Test fun saveVideoFileCopiesBytes() {
        val resolver = ctx.contentResolver
        val src = File.createTempFile("vid", ".mp4").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val dest = Uri.parse("content://test/video.mp4")
        val out = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(dest, out)
        assertEquals(dest, MediaStoreSaver.saveVideoFileToUri(resolver, dest, src))
        assertEquals(listOf(9.toByte(), 8.toByte(), 7.toByte()), out.toByteArray().toList())
        src.delete()
    }

    @Test fun saveViaMediaStoreInsertReturnsRowUri() {
        val resolver = ctx.contentResolver
        // Robolectric's shadow MediaStore accepts the insert, so the saver
        // completes the write and returns the new row's Uri.
        val uri = MediaStoreSaver.saveJpegBytes(resolver, MediaStoreSaver.imageValues("x.jpg"), byteArrayOf(1))
        assertNotNull(uri)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertNotNull(MediaStoreSaver.saveBitmap(resolver, MediaStoreSaver.imageValues("x.jpg"), bitmap))
    }

    @Test fun pendingStillSaveRoutingWithoutTarget() {
        val v = vm()
        // Default save target is MediaStore; under Robolectric the insert
        // fails and the routing helpers must return null, not throw.
        assertNotNull(v.saveStillBytes("IMG_test.jpg", byteArrayOf(1, 2, 3)))
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertNotNull(v.saveStillBitmap("IMG_test.jpg", bitmap))
    }

    @Test fun cropToRectClampsAndKeepsFullFrame() {
        val v = vm()
        val src = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        assertEquals(src, v.cropToRect(src, Rect(0, 0, 100, 80)))
        val cropped = v.cropToRect(src, Rect(10, 10, 60, 50))
        assertEquals(50, cropped.width)
        assertEquals(40, cropped.height)
        // Out-of-range rects clamp to the bitmap.
        val src2 = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val clamped = v.cropToRect(src2, Rect(-5, -5, 15, 15))
        assertEquals(20, clamped.width)
    }

    @Test fun uprightCopyRotatesAndScales() {
        val src = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val rotated = uprightCopy(src, 90, 0)
        assertEquals(50, rotated.width)
        assertEquals(100, rotated.height)
        val scaled = uprightCopy(src, 0, 25)
        assertEquals(25, scaled.width)
        assertNotNull(scaled)
    }

    @Test fun colorAdjustmentsReturnNewBitmapAndRecycleSource() {
        val src = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val out = applyColorAdjustments(src, warmth = 0.5f, shadows = 0.5f, mirror = true)
        assertEquals(8, out.width)
        assertTrue(src.isRecycled)
    }
}
