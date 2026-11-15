package com.vayunmathur.music.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MediaStore readers against awkward rows a real phone produces and the
 * populated FakeMediaProvider does not: untagged files whose title/artist/album
 * columns are null, blank artist names, track 0, and negative years. These are
 * the rows that used to crash the scan (Music's fields are non-null), so the
 * normalisation in getSongs/getArtists is asserted column by column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtilMediaStoreEdgeTest {

    class EdgeMediaProvider : ContentProvider() {
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
            fun row(values: Map<String, Any?>) {
                cursor.addRow(columns.map { values[it] })
            }
            when {
                uri.path?.endsWith("/audio/media") == true -> {
                    row(
                        mapOf(
                            "_id" to 10L, "title" to null, "artist" to null,
                            "artist_id" to 0L, "album" to null, "album_id" to 0L,
                            "duration" to 0L, "track" to 0, "year" to -4,
                        ),
                    )
                    row(
                        mapOf(
                            "_id" to 11L, "title" to "Tagged", "artist" to "",
                            "artist_id" to 5L, "album" to "Record", "album_id" to 6L,
                            "duration" to 42_000L, "track" to 1000, "year" to 1970,
                        ),
                    )
                }
                uri.path?.endsWith("/audio/albums") == true -> {
                    row(mapOf("_id" to 6L, "album" to null, "artist" to null, "artist_id" to 0L))
                }
                uri.path?.endsWith("/audio/artists") == true -> {
                    row(mapOf("_id" to 5L, "artist" to ""))
                    row(mapOf("_id" to 6L, "artist" to null))
                    row(mapOf("_id" to 7L, "artist" to "<unknown>"))
                    row(mapOf("_id" to 8L, "artist" to "Real Artist"))
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

    @Before
    fun setUp() {
        ShadowContentResolver.registerProviderInternal("media", EdgeMediaProvider())
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun songsNormaliseNullTagsAndClampNonPositiveYears(): Unit = runBlocking {
        val songs = getSongs(context).associateBy { it.id }
        val untagged = songs.getValue(10L)
        assertEquals("", untagged.title)
        assertEquals("", untagged.artist)
        assertEquals("", untagged.album)
        assertEquals(0, untagged.year, "a negative MediaStore year must read as unknown (0)")
        assertEquals(1, untagged.discNumber)
        assertEquals(0, untagged.trackNumber)
        assertEquals("content://media/external/audio/media/10", untagged.uri)

        val tagged = songs.getValue(11L)
        assertEquals("Tagged", tagged.title)
        // TRACK 1000 is the first multi-disc encoding: disc 1, track 0.
        assertEquals(1, tagged.discNumber)
        assertEquals(0, tagged.trackNumber)
        assertEquals(1970, tagged.year)
    }

    @Test
    fun albumsNormaliseANullName(): Unit = runBlocking {
        val albums = getAlbums(context)
        assertEquals(1, albums.size)
        assertEquals("", albums.single().name)
        assertEquals("content://media/external/audio/albums/6", albums.single().uri)
    }

    @Test
    fun artistsNormaliseBlankNullAndSentinelNamesToEmpty(): Unit = runBlocking {
        val artists = getArtists(context).associateBy { it.id }
        assertEquals("", artists.getValue(5L).name)
        assertEquals("", artists.getValue(6L).name)
        assertEquals("", artists.getValue(7L).name)
        assertEquals("Real Artist", artists.getValue(8L).name)
    }

    @Test
    fun albumArtistPairsSkipSongsWhoseAlbumOrArtistIsUnknown(): Unit = runBlocking {
        val songs = getSongs(context)
        val albums = getAlbums(context)
        val artists = getArtists(context)
        // Song 11 joins Album 6 / Artist 5; song 10 joins nothing (ids 0).
        assertEquals(
            listOf(albums.single() to artists.first { it.id == 5L }),
            albumArtistPairs(songs, artists, albums),
        )
    }
}
