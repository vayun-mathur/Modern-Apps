package com.vayunmathur.camera.data

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** MediaStoreSaver failure branches: insert/stream errors must not ghost rows. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreSaverFailureTest {

    private val row: Uri = Uri.parse("content://media/external/images/media/7")
    private fun bmp() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private fun resolver(
        insertUri: Uri? = row,
        streamError: Exception? = null,
        streamNull: Boolean = false,
    ): ContentResolver {
        val r = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(r.insert(Mockito.any(Uri::class.java), Mockito.any(ContentValues::class.java)))
            .thenReturn(insertUri)
        if (streamError != null) {
            Mockito.`when`(r.openOutputStream(Mockito.any(Uri::class.java))).thenThrow(streamError)
        } else if (streamNull) {
            Mockito.`when`(r.openOutputStream(Mockito.any(Uri::class.java))).thenReturn(null)
        } else {
            Mockito.`when`(r.openOutputStream(Mockito.any(Uri::class.java)))
                .thenAnswer { ByteArrayOutputStream() }
        }
        return r
    }

    @Test fun insertNullShortCircuitsAllSavers() {
        val r = resolver(insertUri = null)
        assertNull(MediaStoreSaver.saveJpegBytes(r, MediaStoreSaver.imageValues("a.jpg"), byteArrayOf(1)))
        assertNull(MediaStoreSaver.saveBitmap(r, MediaStoreSaver.imageValues("a.jpg"), bmp()))
        val tmp = File.createTempFile("vid", ".mp4").apply { writeBytes(byteArrayOf(1, 2)) }
        assertNull(MediaStoreSaver.saveVideoFile(r, MediaStoreSaver.videoValues("v.mp4"), tmp))
        tmp.delete()
    }

    @Test fun streamFailuresDeleteGhostRowAndReturnNull() {
        for (error in listOf(FileNotFoundException("gone"), SecurityException("denied"))) {
            val r = resolver(streamError = error)
            assertNull(MediaStoreSaver.saveJpegBytes(r, MediaStoreSaver.imageValues("a.jpg"), byteArrayOf(1)), "$error")
            Mockito.verify(r).delete(row, null, null)
        }
        // IOException mid-write (a stream that fails on write) likewise cleans up.
        val ioResolver = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(ioResolver.insert(Mockito.any(Uri::class.java), Mockito.any(ContentValues::class.java)))
            .thenReturn(row)
        Mockito.`when`(ioResolver.openOutputStream(Mockito.any(Uri::class.java)))
            .thenReturn(object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int): Unit = throw IOException("disk full")
            })
        assertNull(MediaStoreSaver.saveJpegBytes(ioResolver, MediaStoreSaver.imageValues("a.jpg"), byteArrayOf(1)))
        Mockito.verify(ioResolver).delete(row, null, null)
        val r2 = resolver(streamNull = true)
        assertNull(MediaStoreSaver.saveBitmap(r2, MediaStoreSaver.imageValues("a.jpg"), bmp()))
        Mockito.verify(r2).delete(row, null, null)
    }

    @Test fun toUriVariantsReturnNullOnFailureAndDestOnSuccess() {
        val dest = Uri.parse("content://docs/tree/1/document/img.jpg")
        val failing = resolver(streamError = SecurityException("denied"))
        assertNull(MediaStoreSaver.saveJpegBytesToUri(failing, dest, byteArrayOf(1)))
        assertNull(MediaStoreSaver.saveBitmapToUri(failing, dest, bmp()))
        val tmp = File.createTempFile("vid", ".mp4").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        assertNull(MediaStoreSaver.saveVideoFileToUri(failing, dest, tmp))
        val ok = resolver()
        assertEquals(dest, MediaStoreSaver.saveJpegBytesToUri(ok, dest, byteArrayOf(1, 2, 3)))
        assertEquals(dest, MediaStoreSaver.saveVideoFileToUri(ok, dest, tmp))
        assertNotNull(MediaStoreSaver.saveBitmapToUri(ok, dest, bmp()))
        tmp.delete()
    }

    @Test fun videoFileSuccessCopiesBytes() {
        val out = ByteArrayOutputStream()
        val r = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(r.insert(Mockito.any(Uri::class.java), Mockito.any(ContentValues::class.java))).thenReturn(row)
        Mockito.`when`(r.openOutputStream(Mockito.any(Uri::class.java))).thenReturn(out)
        val tmp = File.createTempFile("vid", ".mp4").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
        assertEquals(row, MediaStoreSaver.saveVideoFile(r, MediaStoreSaver.videoValues("v.mp4"), tmp))
        assertTrue(out.toByteArray().contentEquals(byteArrayOf(4, 5, 6, 7)))
        tmp.delete()
    }
}
