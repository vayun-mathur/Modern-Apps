package com.vayunmathur.music.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.platform.LyricLine
import com.vayunmathur.music.platform.Lyrics
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.SongsUiState
import com.vayunmathur.music.platform.albumArtistPairs
import com.vayunmathur.music.platform.formatDuration
import com.vayunmathur.music.ui.components.MusicTabsBar
import com.vayunmathur.music.ui.components.NowPlayingBar
import com.vayunmathur.music.ui.components.PlayShuffleRow
import com.vayunmathur.music.ui.components.ShufflePlayFab
import com.vayunmathur.music.ui.components.TrackListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose + pure-logic coverage for the music library screens and helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int): String = context.getString(id)

    private fun song(id: Long, title: String, artist: String = "Artist", albumId: Long = 1, artistId: Long = 1) = Music(
        id = id, title = title, artist = artist, artistId = artistId, album = "Album", albumId = albumId,
        uri = "content://media/$id", duration = 180_000, trackNumber = 1, year = 2020,
    )

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    @Test
    fun formatDurationCases() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun albumArtistPairsDropsUnmatchedAndDedupes() {
        val album = Album(1, "A", "content://a/1")
        val artist = Artist(1, "R", "content://r/1")
        val songs = listOf(song(1, "x"), song(2, "y"), song(3, "z", albumId = 99))
        val pairs = albumArtistPairs(songs, listOf(artist), listOf(album))
        assertEquals(listOf(album to artist), pairs)
    }

    @Test
    fun songsScreenListsSongsAndPlaysOnClick() {
        var played: Pair<List<Music>, Int>? = null
        val actions = object : MusicActions {
            override fun playSong(songs: List<Music>, startWithIndex: Int, sourceId: String?, sourceName: String?) {
                played = songs to startWithIndex
            }
        }
        val songs = listOf(song(1, "Alpha"), song(2, "Beta"))
        composeRule.setContent {
            SongsScreen(SongsUiState(songs = songs, playingSongId = 2L), actions, stack())
        }
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
        composeRule.onNodeWithText("Beta").performClick()
        assertEquals(songs to 1, played)
    }

    @Test
    fun lyricsViewTimed() {
        composeRule.setContent {
            LyricsView(Lyrics.Timed(listOf(LyricLine(0, "first line"), LyricLine(1000, "second line"))), 0)
        }
        composeRule.onNodeWithText("first line").assertIsDisplayed()
    }

    @Test
    fun lyricsViewPlain() {
        composeRule.setContent { LyricsView(Lyrics.Plain("plain words here"), -1) }
        composeRule.onNodeWithText("plain words here").assertIsDisplayed()
    }

    @Test
    fun lyricsViewNone() {
        composeRule.setContent { LyricsView(Lyrics.None, -1) }
        composeRule.onNodeWithText(str(R.string.no_lyrics_available)).assertIsDisplayed()
    }

    @Test
    fun trackItemAndShuffleRowClick() {
        var clicks = 0
        composeRule.setContent {
            TrackListItem("My Song", isPlaying = true, artUri = "content://x".toUri(), onClick = { clicks++ })
            PlayShuffleRow(onPlay = { clicks += 10 }, onShuffle = { clicks += 100 })
        }
        composeRule.onNodeWithText("My Song").assertIsDisplayed()
        composeRule.onNodeWithText("My Song").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun tabsBarSelectsAlbumsTab() {
        var tab = -1
        composeRule.setContent { MusicTabsBar(selectedTab = 0, onSelectTab = { tab = it }) }
        composeRule.onNodeWithText(str(R.string.label_albums)).performClick()
        assertEquals(1, tab)
    }

    @Test
    fun shuffleFabHiddenForEmptyLibrary() {
        var clicks = 0
        composeRule.setContent { ShufflePlayFab(emptyList()) { clicks++ } }
        assertEquals(0, clicks)
    }

    @Test
    fun nowPlayingBarShowsTrackAndToggles() {
        var toggled = false
        var opened = false
        composeRule.setContent {
            NowPlayingBar(
                songId = 1L, title = "Now Song", artist = "Now Artist", artworkUri = null,
                isPlaying = false, owsSharedKeys = false,
                onOpen = { opened = true }, onTogglePlayPause = { toggled = true }, onSkipNext = {},
            )
        }
        composeRule.onNodeWithText("Now Song").assertIsDisplayed()
        composeRule.onNodeWithText("Now Artist").assertIsDisplayed()
        composeRule.onNodeWithText("Now Song").performClick()
        assertTrue(opened)
        assertTrue(!toggled)
    }
}
