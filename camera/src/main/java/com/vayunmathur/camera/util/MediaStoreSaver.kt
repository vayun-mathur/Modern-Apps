package com.vayunmathur.camera.util

import com.vayunmathur.library.log.Log
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Centralizes the DCIM/Camera MediaStore writes shared by photo, video and panorama capture. */
object MediaStoreSaver {

    private val FileStamp = LocalDateTime.Format {
        year(); monthNumber(); day(); char('_'); hour(); minute(); second()
    }

    // Millisecond suffix: two singles in the same second otherwise share IMG_<ts>.jpg
    // (burst adds its own suffix, singles don't), producing confusing duplicates.
    /** Nanos per milli; millisecond suffix keeps same-second singles unique. */
    private const val NANOS_PER_MILLI = 1_000_000

    fun timestamp(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return now.format(FileStamp) + "_%03d".format(now.nanosecond / NANOS_PER_MILLI)
    }

    fun imageValues(displayName: String): ContentValues = contentValues(displayName, "image/jpeg")

    fun videoValues(displayName: String): ContentValues = contentValues(displayName, "video/mp4")

    private fun contentValues(displayName: String, mimeType: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
    }

    fun saveBitmap(
        resolver: ContentResolver,
        values: ContentValues,
        bitmap: Bitmap,
        quality: Int = 95,
    ): Uri? {
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = try {
            resolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
            } ?: false
        } catch (e: java.io.FileNotFoundException) {
            Log.status("MediaStoreSaver", "saveBitmap write failed for $uri", e)
            false
        } catch (e: java.io.IOException) {
            Log.status("MediaStoreSaver", "saveBitmap write failed for $uri", e)
            false
        } catch (e: SecurityException) {
            Log.status("MediaStoreSaver", "saveBitmap write failed for $uri", e)
            false
        }
        // A failed write must not leave a 0-byte ghost row that becomes the gallery thumbnail.
        if (!ok) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
        return uri
    }

    /** Write pre-encoded JPEG [bytes] (e.g. with injected XMP) to the image store. */
    fun saveJpegBytes(
        resolver: ContentResolver,
        values: ContentValues,
        bytes: ByteArray,
    ): Uri? {
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = try {
            resolver.openOutputStream(uri)?.use { os ->
                os.write(bytes)
                true
            } ?: false
        } catch (e: java.io.FileNotFoundException) {
            Log.status("MediaStoreSaver", "saveJpegBytes write failed for $uri", e)
            false
        } catch (e: java.io.IOException) {
            Log.status("MediaStoreSaver", "saveJpegBytes write failed for $uri", e)
            false
        } catch (e: SecurityException) {
            Log.status("MediaStoreSaver", "saveJpegBytes write failed for $uri", e)
            false
        }
        if (!ok) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
        return uri
    }

    /** Write a [bitmap] to a caller-supplied [dest] (e.g. a pre-created SAF document). */
    fun saveBitmapToUri(
        resolver: ContentResolver,
        dest: Uri,
        bitmap: Bitmap,
        quality: Int = 95,
    ): Uri? {
        val ok = try {
            resolver.openOutputStream(dest)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
            } ?: false
        } catch (e: java.io.FileNotFoundException) {
            Log.status("MediaStoreSaver", "saveBitmapToUri failed for $dest", e)
            false
        } catch (e: java.io.IOException) {
            Log.status("MediaStoreSaver", "saveBitmapToUri failed for $dest", e)
            false
        } catch (e: SecurityException) {
            Log.status("MediaStoreSaver", "saveBitmapToUri failed for $dest", e)
            false
        }
        return if (ok) dest else null
    }

    /** Write pre-encoded JPEG [bytes] to a caller-supplied [dest]. */
    fun saveJpegBytesToUri(
        resolver: ContentResolver,
        dest: Uri,
        bytes: ByteArray,
    ): Uri? {
        val ok = try {
            resolver.openOutputStream(dest)?.use { os ->
                os.write(bytes)
                true
            } ?: false
        } catch (e: java.io.FileNotFoundException) {
            Log.status("MediaStoreSaver", "saveJpegBytesToUri failed for $dest", e)
            false
        } catch (e: java.io.IOException) {
            Log.status("MediaStoreSaver", "saveJpegBytesToUri failed for $dest", e)
            false
        } catch (e: SecurityException) {
            Log.status("MediaStoreSaver", "saveJpegBytesToUri failed for $dest", e)
            false
        }
        return if (ok) dest else null
    }

    /** Copy a staged [file] into a caller-supplied [dest] (e.g. SAF video doc). */
    fun saveVideoFileToUri(resolver: ContentResolver, dest: Uri, file: File): Uri? {
        val ok = try {
            resolver.openOutputStream(dest)?.use { os ->
                file.inputStream().use { input -> input.copyTo(os) }
                true
            } ?: false
        } catch (e: java.io.FileNotFoundException) {
            Log.status("MediaStoreSaver", "saveVideoFileToUri failed for $dest", e)
            false
        } catch (e: java.io.IOException) {
            Log.status("MediaStoreSaver", "saveVideoFileToUri failed for $dest", e)
            false
        } catch (e: SecurityException) {
            Log.status("MediaStoreSaver", "saveVideoFileToUri failed for $dest", e)
            false
        }
        return if (ok) dest else null
    }

    fun saveVideoFile(resolver: ContentResolver, values: ContentValues, file: File): Uri? {
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = try {
            resolver.openOutputStream(uri)?.use { os ->
                file.inputStream().use { input -> input.copyTo(os) }
                true
            } ?: false
        } catch (e: java.io.FileNotFoundException) {
            Log.status("MediaStoreSaver", "saveVideoFile write failed for $uri", e)
            false
        } catch (e: java.io.IOException) {
            Log.status("MediaStoreSaver", "saveVideoFile write failed for $uri", e)
            false
        } catch (e: SecurityException) {
            Log.status("MediaStoreSaver", "saveVideoFile write failed for $uri", e)
            false
        }
        if (!ok) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
        return uri
    }
}
