package com.vayunmathur.music.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.NowPlayingUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingMoreTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    private fun song(id: Long, title: String) = Music(
        id = id, title = title, artist = "Artist", artistId = 1, album = "Album", albumId = 7,
        uri = "content://media/$id", duration = 180_000, trackNumber = id.toInt(), year = 2020,
    )

    @Test
    fun `wide layout shows both slots`() {
        composeRule.setContent {
            NowPlayingWideLayout(player = { Text("player slot") }, queue = { Text("queue slot") })
        }
        composeRule.onNodeWithText("player slot").assertIsDisplayed()
        composeRule.onNodeWithText("queue slot").assertIsDisplayed()
    }

    @Test
    fun `queue list shows its title over an empty queue`() {
        var jumped = -1
        composeRule.setContent {
            QueueList(emptyList(), playingSongId = null, title = "Up next", onJump = { jumped = it })
        }
        composeRule.onNodeWithText("Up next").assertIsDisplayed()
        assertEquals(-1, jumped)
    }

    @Test
    fun `queue list jumps by index`() {
        var jumped = -1
        val songs = listOf(song(1, "First"), song(2, "Second"), song(3, "Third"))
        composeRule.setContent {
            QueueList(songs, playingSongId = 3L, title = "Playing next", onJump = { jumped = it })
        }
        composeRule.onNodeWithText("First").assertIsDisplayed()
        composeRule.onNodeWithText("Third").performClick()
        assertEquals(2, jumped)
    }

    @Test
    fun `now playing with synced lyrics and playing state`() {
        var toggled = false
        val actions = object : MusicActions {
            override fun togglePlayPause() { toggled = true }
        }
        composeRule.setContent {
            NowPlayingScreen(
                NowPlayingUiState(
                    title = "Lyric Song", artist = "Singer", album = "Records",
                    songId = 9L, isPlaying = true, durationMs = 240_000, positionMs = 61_000,
                    lyrics = "[00:01.00]first line\n[01:01.00]second line",
                ),
                actions, stack(),
            ) { Text("queue body") }
        }
        composeRule.onNodeWithText("Lyric Song").assertIsDisplayed()
        composeRule.onNodeWithText("Singer").assertIsDisplayed()
        assertTrue(!toggled)
    }

    @Test
    fun `now playing queue slot composes its content`() {
        composeRule.setContent {
            NowPlayingScreen(
                NowPlayingUiState(
                    title = "Plain Song", artist = "Singer", album = "",
                    songId = null, isPlaying = false, durationMs = 0, positionMs = 0,
                ),
                MusicActions.Noop, stack(),
            ) { Text("queue body") }
        }
        composeRule.onNodeWithText("Plain Song").assertIsDisplayed()
    }
}
