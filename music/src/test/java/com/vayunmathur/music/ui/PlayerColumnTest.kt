package com.vayunmathur.music.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.LyricLine
import com.vayunmathur.music.platform.Lyrics
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.NowPlayingUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PlayerColumn (the now-playing column shared by the compact and expanded
 * players) composed directly, so its song-info navigation, progress labels,
 * and lyrics/artwork swap are asserted without a live player. Transport
 * icon buttons carry only icon content-descriptions, so transport dispatch is
 * asserted at the NowPlayingScreen level in NowPlayingControlsTest instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerColumnTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home, Route.Song))

    private fun state(
        artistId: Long? = 31L,
        albumId: Long? = 7L,
        album: String = "Great Album",
        repeatMode: Int = Player.REPEAT_MODE_ONE,
    ) = NowPlayingUiState(
        title = "Column Song", artist = "Column Artist", album = album,
        songId = 4L, artistId = artistId, albumId = albumId,
        isPlaying = true, positionMs = 90_000, durationMs = 240_000,
        shuffle = true, repeatMode = repeatMode,
    )

    @Test
    fun columnShowsSongInfoAndProgressLabels() {
        composeRule.setContent {
            PlayerColumn(
                state(), MusicActions.Noop, stack(),
                songId = 4L, leaving = false,
                lyrics = Lyrics.None, currentIndex = -1,
                showLyrics = false, onToggleLyrics = {},
            )
        }
        composeRule.onNodeWithText("Column Song").assertIsDisplayed()
        composeRule.onNodeWithText("Column Artist").assertIsDisplayed()
        composeRule.onNodeWithText("Great Album").assertIsDisplayed()
        composeRule.onAllNodesWithText("1:30").assertCountEquals(1)
        composeRule.onAllNodesWithText("4:00").assertCountEquals(1)
    }

    @Test
    fun artistAndAlbumLinesNavigateToTheirDetailRoutes() {
        val nav = stack()
        composeRule.setContent {
            PlayerColumn(
                state(), MusicActions.Noop, nav,
                songId = 4L, leaving = false,
                lyrics = Lyrics.None, currentIndex = -1,
                showLyrics = false, onToggleLyrics = {},
            )
        }
        composeRule.onNodeWithText("Column Artist").performClick()
        assertEquals(Route.ArtistDetail(31L), nav.last())

        composeRule.onNodeWithText("Great Album").performClick()
        assertEquals(Route.AlbumDetail(7L), nav.last())
    }

    @Test
    fun lyricsSwapShowsTimedLinesInsteadOfArtwork() {
        composeRule.setContent {
            PlayerColumn(
                state(), MusicActions.Noop, stack(),
                songId = 4L, leaving = true,
                lyrics = Lyrics.Timed(listOf(LyricLine(0, "sung line"), LyricLine(90_000, "current line"))),
                currentIndex = 1,
                showLyrics = true, onToggleLyrics = {},
            )
        }
        composeRule.onNodeWithText("sung line").assertIsDisplayed()
        composeRule.onNodeWithText("current line").assertIsDisplayed()
        composeRule.onNodeWithText("Column Song").assertIsDisplayed()
    }

    @Test
    fun plainLyricsAndNoIdsStillRenderTitleAndZeroedAlbumLine() {
        val nav = stack()
        composeRule.setContent {
            PlayerColumn(
                state(artistId = null, albumId = null, album = "", repeatMode = Player.REPEAT_MODE_OFF),
                MusicActions.Noop, nav,
                songId = null, leaving = false,
                lyrics = Lyrics.Plain("plain column words"),
                currentIndex = -1,
                showLyrics = true, onToggleLyrics = {},
            )
        }
        composeRule.onNodeWithText("Column Song").assertIsDisplayed()
        composeRule.onNodeWithText("plain column words").assertIsDisplayed()
        // No linked ids: the artist line is inert text.
        composeRule.onNodeWithText("Column Artist").performClick()
        assertEquals(Route.Song, nav.last())
    }
}
