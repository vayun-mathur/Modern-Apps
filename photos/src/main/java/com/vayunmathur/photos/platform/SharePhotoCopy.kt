package com.vayunmathur.photos.platform

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Prepares the copy of a photo that leaves the device through the app's own
 * share entry.
 *
 * Sharing the MediaStore URI directly ships the original file, GPS EXIF
 * included. For stills this instead copies the bytes into the app cache,
 * removes every GPS EXIF tag from the copy, and hands out a FileProvider URI
 * for the copy. The original is never modified. Videos return the original
 * URI: their location metadata is not EXIF and is out of scope here.
 *
 * Stripping is unconditional (always on) for this share path: there is no
 * photos settings screen to host an opt-out toggle, and a share that keeps
 * the sender's location should not be one tap away from the default. If a
 * still cannot be copied or stripped, callers fall back to the original URI
 * so a share still works; the failure is logged.
 */
object SharePhotoCopy {

    private const val TAG = "SharePhotoCopy"

    /** EXIF tags that carry location; every one is removed from a shared copy. */
    internal val GPS_TAGS: List<String> = listOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_VERSION_ID,
    )

    /** Remove every [GPS_TAGS] entry from [file]'s EXIF, in place. Non-GPS tags are kept. */
    internal fun stripGpsExif(file: File) {
        val exif = ExifInterface(file)
        for (tag in GPS_TAGS) exif.setAttribute(tag, null)
        exif.saveAttributes()
    }

    /** True when the shared copy for [file] no longer reports a GPS position. */
    internal fun hasGpsExif(file: File): Boolean = ExifInterface(file).latLong != null

    /**
     * The URI to put in a share intent for [source]: a GPS-stripped cache copy
     * for stills, the original for videos (or anything not an image). Null if
     * a still's copy could not be made, so the caller can decide its fallback.
     */
    suspend fun shareUri(context: Context, source: Uri, displayName: String, mimeType: String?): Uri? =
        withContext(Dispatchers.IO) {
            if (mimeType != null && !mimeType.startsWith("image/")) return@withContext source
            try {
                val dir = File(context.cacheDir, "share_stripped").apply { mkdirs() }
                val safeName = displayName.substringAfterLast('/').ifBlank { "photo" }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val copy = File(dir, safeName)
                context.contentResolver.openInputStream(source)?.use { input ->
                    copy.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                stripGpsExif(copy)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
            } catch (e: IOException) {
                Log.error(TAG, "Could not prepare a location-stripped copy for sharing", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "Could not prepare a location-stripped copy for sharing", e)
                null
            } catch (e: SecurityException) {
                Log.error(TAG, "Could not prepare a location-stripped copy for sharing", e)
                null
            }
        }
}
