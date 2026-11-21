package com.vayunmathur.photos.platform

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import com.vayunmathur.photos.data.Photo

/**
 * Moving photos between albums, expressed purely through MediaStore folders.
 *
 * An album is a media folder whose BUCKET_DISPLAY_NAME is the album name: images
 * live under `Pictures/<album>` and videos under `Movies/<album>`, both of which
 * yield `BUCKET_DISPLAY_NAME == "<album>"`, so [GalleryViewModel.albums] (which
 * groups by [Photo.album]) picks the move up unchanged.
 *
 * Callers must hold write access to the items first — most library photos are not
 * owned by this app, so the binder runs [MediaStore.createWriteRequest] over
 * [urisOf] before invoking [applyMove]. The UI ensures MANAGE_MEDIA
 * contextually before that (rememberManageMediaAction, deferred from app
 * start), so the direct [resolver.update][android.content.ContentResolver.update]
 * calls below apply silently; without the grant they throw
 * [SecurityException] and the item is logged and skipped.
 */
object AlbumMediaStore {
    private const val TAG = "AlbumMediaStore"

    /** Collapse a user-entered name into a single safe path segment. */
    fun sanitizeAlbumName(raw: String): String =
        raw.trim().replace(Regex("""[/\\]+"""), "_")

    /** The write-consent target URIs for [photos]. */
    fun urisOf(photos: List<Photo>): List<Uri> = photos.map { it.uri.toUri() }

    /**
     * The RELATIVE_PATH [photo] should get to live in [album], or the media
     * type's default root when [album] is null/blank ("remove from album").
     */
    private fun relativePathFor(photo: Photo, album: String?): String {
        val root = if (photo.videoData != null) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        return if (album.isNullOrBlank()) root else "$root/$album"
    }

    /**
     * Update each item's RELATIVE_PATH so it moves into [album] (null = default
     * root). Returns the ids that were actually moved, so the caller can update
     * just those rows optimistically.
     */
    fun applyMove(context: Context, photos: List<Photo>, album: String?): List<Long> {
        val resolver = context.contentResolver
        val moved = mutableListOf<Long>()
        for (photo in photos) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(photo, album))
                }
                if (resolver.update(photo.uri.toUri(), values, null, null) > 0) {
                    moved += photo.id
                }
            } catch (e: SecurityException) {
                Log.error(TAG, "Failed to move photo ${photo.id} to album=$album", e)
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "Failed to move photo ${photo.id} to album=$album", e)
            }
        }
        return moved
    }
}
