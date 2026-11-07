package com.vayunmathur.music.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.platform.AlbumDetailUiState
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.NowPlayingUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Album detail, now-playing and queue list rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicDetailScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun song(id: Long, title: String) = Music(
        id = id, title = title, artist = "Artist", artistId = 1, album = "Album", albumId = 7,
        uri = "content://media/$id", duration = 180_000, trackNumber = id.toInt(), year = 2020,
    )

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    @Test
    fun albumDetailShowsHeaderAndTracksPlaysOnClick() {
        var playedIndex = -1
        val actions = object : MusicActions {
            override fun playSong(songs: List<Music>, startWithIndex: Int, sourceId: String?, sourceName: String?) {
                playedIndex = startWithIndex
            }
        }
        val tracks = listOf(song(1, "Track One"), song(2, "Track Two"))
        composeRule.setContent {
            AlbumDetailContent(
                AlbumDetailUiState(
                    albumId = 7, name = "Great Album", artistName = "Great Artist",
                    info = "2020 • 2 songs", tracks = tracks, playingSongId = 1L,
                ),
                actions, stack(),
            )
        }
        composeRule.onNodeWithText("Great Album").assertIsDisplayed()
        composeRule.onNodeWithText("Great Artist").assertIsDisplayed()
    }

    @Test
    fun nowPlayingShowsTitleArtistAndTransport() {
        var toggled = false
        var skipped = false
        val actions = object : MusicActions {
            override fun togglePlayPause() { toggled = true }
            override fun skipNext() { skipped = true }
        }
        composeRule.setContent {
            NowPlayingScreen(
                NowPlayingUiState(
                    title = "Current Song", artist = "Current Artist", album = "Current Album",
                    songId = 5L, isPlaying = false, durationMs = 200_000, positionMs = 50_000,
                ),
                actions, stack(),
            )
        }
        composeRule.onNodeWithText("Current Song").assertIsDisplayed()
        composeRule.onNodeWithText("Current Artist").assertIsDisplayed()
        assertTrue(!toggled && !skipped)
    }

    @Test
    fun queueListJumpsOnClick() {
        var jumped = -1
        val songs = listOf(song(1, "Queued One"), song(2, "Queued Two"))
        composeRule.setContent {
            QueueList(songs, playingSongId = 1L, title = "Queue", onJump = { jumped = it })
        }
        composeRule.onNodeWithText("Queued Two").assertIsDisplayed()
        composeRule.onNodeWithText("Queued Two").performClick()
        assertEquals(1, jumped)
    }
}
