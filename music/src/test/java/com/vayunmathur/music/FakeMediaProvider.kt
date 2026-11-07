package com.vayunmathur.music

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * A fake `media` authority for Robolectric: Robolectric's own MediaStore
 * is empty, so the MediaStore readers in Util.kt can only ever be tested
 * against "no music on this phone". This provider answers the three audio
 * queries (media/albums/artists) with a small fixed library, including the
 * awkward rows real phones produce (multi-disc track numbers, a missing
 * year, the `<unknown>` artist sentinel).
 */
class FakeMediaProvider : ContentProvider() {

    companion object {
        /**
         * Robolectric's provider registry and the repository singleton both
         * outlive a test class in a shared sandbox, so the populated tests
         * switch this off (and refresh) on the way out: later classes then
         * query an authoritative empty library instead of this fixture.
         */
        @Volatile var enabled: Boolean = true
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: emptyArray()
        val cursor = MatrixCursor(columns)
        if (!enabled) return cursor
        fun row(values: Map<String, Any?>) {
            cursor.addRow(columns.map { values[it] })
        }
        when {
            uri.path?.endsWith("/audio/media") == true -> {
                // _id, title, artist, artist_id, album, album_id, duration, track, year
                row(
                    mapOf(
                        "_id" to 1L, "title" to "Beta Song", "artist" to "Artist One",
                        "artist_id" to 11L, "album" to "Album One", "album_id" to 21L,
                        "duration" to 200_000L, "track" to 1002, "year" to 2019,
                    ),
                )
                row(
                    mapOf(
                        "_id" to 2L, "title" to "Alpha Song", "artist" to "Artist One",
                        "artist_id" to 11L, "album" to "Album One", "album_id" to 21L,
                        "duration" to 180_000L, "track" to 2005, "year" to 0,
                    ),
                )
                row(
                    mapOf(
                        "_id" to 3L, "title" to "Gamma Song", "artist" to "<unknown>",
                        "artist_id" to 12L, "album" to "Album Two", "album_id" to 22L,
                        "duration" to 60_000L, "track" to 7, "year" to 1999,
                    ),
                )
            }
            uri.path?.endsWith("/audio/albums") == true -> {
                row(mapOf("_id" to 21L, "album" to "Album One", "artist" to "Artist One", "artist_id" to 11L))
                row(mapOf("_id" to 22L, "album" to "Album Two", "artist" to "<unknown>", "artist_id" to 12L))
            }
            uri.path?.endsWith("/audio/artists") == true -> {
                row(mapOf("_id" to 11L, "artist" to "Artist One"))
                row(mapOf("_id" to 12L, "artist" to "<unknown>"))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
