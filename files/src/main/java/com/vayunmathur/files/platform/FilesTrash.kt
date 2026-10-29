package com.vayunmathur.files.platform

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * System-trash (MediaStore) helpers behind [FilesViewModel]'s trash-first delete.
 *
 * This is the platform trash, not an app-owned `.trash` folder: trashed items live in the
 * shared MediaStore trash with 30-day auto-delete, restore, and per-batch user-consent dialogs.
 * Only MediaStore-indexed files resolve to trashable Uris; directories and unindexed files
 * cannot be trashed and fall back to a permanent-delete confirmation.
 *
 * Implementation lives in `FilesTrashModel.kt`; this file keeps only the stateless queries
 * so the behaviour layer stays under the function cap.
 */

private const val TRASH_QUERY_LIMIT = 2000
private const val MILLIS_PER_SECOND = 1000L

/** MediaStore Uris for the subset of [files] that the platform can trash. */
internal fun resolveTrashableUris(context: Context, files: List<File>): Map<File, Uri> {
    if (files.isEmpty()) return emptyMap()
    val collection = MediaStore.Files.getContentUri("external")
    val out = mutableMapOf<File, Uri>()
    for (file in files) {
        if (file.isDirectory) continue
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(file.absolutePath),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    mediaItemUri(c.getInt(1), c.getLong(0))?.let { out[file] = it }
                }
            }
        } catch (_: IllegalArgumentException) {
        }
    }
    return out
}

/**
 * The per-collection Uri MediaStore's trash/delete requests require. They reject generic
 * `Files` Uris ("All requested items must be Media items"), so non-media files get `null`.
 */
private fun mediaItemUri(mediaType: Int, id: Long): Uri? {
    val collection = when (mediaType) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri("external")
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri("external")
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri("external")
        else -> return null
    }
    return android.content.ContentUris.withAppendedId(collection, id)
}

/** All items currently in the system trash, newest first. Files only — the platform trash. */
internal fun queryTrashedItems(context: Context, limit: Int = TRASH_QUERY_LIMIT): List<FileBrowserItem> {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )
    val queryArgs = android.os.Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        putString(
            android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )
        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
    }
    val out = mutableListOf<FileBrowserItem>()
    try {
        context.contentResolver.query(collection, projection, queryArgs, null)?.use { c ->
            readTrashRows(c, out)
        }
    } catch (_: IllegalArgumentException) {
    }
    return out
}

private fun readTrashRows(
    cursor: android.database.Cursor,
    out: MutableList<FileBrowserItem>
) {
    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
    val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
    val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
    val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
    val typeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
    while (cursor.moveToNext()) {
        val data = cursor.getString(dataIdx) ?: continue
        val file = File(data)
        val displayName = cursor.getString(nameIdx) ?: file.name
        val id = cursor.getLong(idIdx)
        val size = cursor.getLong(sizeIdx).takeIf { !cursor.isNull(sizeIdx) }
        val modified = cursor.getLong(dateIdx).takeIf { !cursor.isNull(dateIdx) }
            ?.times(MILLIS_PER_SECOND) ?: 0L
        out.add(
            FileBrowserItem(
                name = displayName,
                isDirectory = false,
                size = size,
                realFile = file,
                zipInnerPath = null,
                key = "trash:$id",
                lastModified = modified,
                mediaUri = mediaItemUri(cursor.getInt(typeIdx), id),
            ),
        )
    }
}
