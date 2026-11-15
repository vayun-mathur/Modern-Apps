package com.vayunmathur.music.platform

import android.content.Context
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Util.kt beyond the already-covered formatters: library grouping with several
 * artists/albums, artwork/thumbnail failure paths (no MediaStore art on a host
 * JVM), collage geometry, and the AlbumArt composables' placeholder branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtilExpandedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun song(id: Long, albumId: Long, artistId: Long) = Music(
        id = id, title = "Song $id", artist = "Artist $artistId", artistId = artistId,
        album = "Album $albumId", albumId = albumId, uri = "content://media/$id",
        duration = 100_000, trackNumber = 1, year = 2000,
    )

    @Test
    fun `format duration covers minute hour and multi hour boundaries`() {
        assertEquals("0:00", formatDuration(999))
        assertEquals("0:59", formatDuration(59_999))
        assertEquals("1:00", formatDuration(60_000))
        assertEquals("59:59", formatDuration(3_599_000))
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("2:03:07", formatDuration(7_387_000))
        assertEquals("10:00:00", formatDuration(36_000_000))
    }

    @Test
    fun `album artist pairs join across albums and drop songs with a missing side`() {
        val albumA = Album(21, "A", "content://a/21")
        val albumB = Album(22, "B", "content://a/22")
        val artistA = Artist(11, "RA", "content://r/11")
        val artistB = Artist(12, "RB", "content://r/12")
        val songs = listOf(
            song(1, albumId = 21, artistId = 11),
            song(2, albumId = 21, artistId = 12),
            song(3, albumId = 22, artistId = 12),
            song(4, albumId = 99, artistId = 11),
            song(5, albumId = 21, artistId = 99),
            song(6, albumId = 21, artistId = 11),
        )
        val pairs = albumArtistPairs(songs, listOf(artistA, artistB), listOf(albumA, albumB))
        assertEquals(
            setOf(albumA to artistA, albumA to artistB, albumB to artistB),
            pairs.toSet(),
        )
        assertEquals(3, pairs.size, "duplicate joins from songs 1 and 6 must dedupe")
        assertEquals(emptyList(), albumArtistPairs(emptyList(), listOf(artistA), listOf(albumA)))
    }

    @Test
    fun `thumbnail metadata and artwork reads fail soft for unreadable uris`() {
        val bad = "content://media/external/audio/media/424242".toUri()
        assertNull(getThumbnail(context, bad))
        assertEquals(0L, getRealAudioDuration(context, bad))
        assertEquals(0, getAudioYear(context, bad))
        assertNull(artworkBytes(context, bad, null))
        assertNull(artworkBytes(context, bad, bad))
    }

    @Test
    fun `collage is always a square canvas even when no tile decodes`() {
        val empty = createCollageBitmap(context, emptyList())
        assertEquals(512, empty.width)
        assertEquals(512, empty.height)

        val noArt = createCollageBitmap(context, listOf("content://media/1".toUri()))
        assertEquals(512, noArt.width)
        assertEquals(512, noArt.height)
        assertNotNull(noArt)
    }

    @Test
    fun `album art composables render placeholders without usable artwork`() {
        val noArt: Uri? = null
        composeRule.setContent {
            AlbumArt(noArt, Modifier)
            AlbumArt(emptyList<Uri>(), Modifier)
        }
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun `single album art renders a placeholder when the thumbnail is missing`() {
        composeRule.setContent {
            AlbumArt("content://media/external/audio/media/9".toUri(), Modifier)
        }
        composeRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun `collage album art renders a placeholder when no tile decodes`() {
        composeRule.setContent {
            AlbumArt(listOf("content://media/external/audio/media/9".toUri()), Modifier)
        }
        composeRule.onRoot().assertIsDisplayed()
    }
}
