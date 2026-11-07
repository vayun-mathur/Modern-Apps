package com.vayunmathur.music.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.media3.common.Player
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
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
class NowPlayingControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    private class RecordingActions : MusicActions {
        var toggled = 0
        var next = 0
        var previous = 0
        var shuffle = 0
        var repeat = 0
        var seek: Long? = null
        override fun togglePlayPause() { toggled++ }
        override fun skipNext() { next++ }
        override fun skipPrevious() { previous++ }
        override fun toggleShuffle() { shuffle++ }
        override fun toggleRepeat() { repeat++ }
        override fun seekTo(pos: Long) { seek = pos }
    }

    @Test
    fun `progress texts and seek slider report fractions of the duration`() {
        val actions = RecordingActions()
        composeRule.setContent {
            NowPlayingScreen(
                NowPlayingUiState(
                    title = "Timed", artist = "Artist", album = "Album",
                    songId = 1L, isPlaying = false, durationMs = 240_000, positionMs = 60_000,
                ),
                actions, stack(),
            )
        }
        // Both progress labels compose (they may sit below the fold).
        composeRule.onAllNodesWithText("1:00").assertCountEquals(1)
        composeRule.onAllNodesWithText("4:00").assertCountEquals(1)
        // Dragging the progress slider must seek: 60s of 240s starts at 0.25.
        composeRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f)))
            .performTouchInput { swipe(Offset(200f, center.y), Offset(600f, center.y), 300L) }
        assertTrue(actions.seek != null, "seeking must reach the actions")
        assertTrue(actions.seek!! in 60_000..240_000, "seek was ${actions.seek}")
    }

    @Test
    fun `zero duration renders zeros and a safe slider`() {
        val actions = RecordingActions()
        composeRule.setContent {
            NowPlayingScreen(
                NowPlayingUiState(
                    title = "No Duration", artist = "Artist", album = "",
                    songId = null, isPlaying = true, durationMs = 0, positionMs = 0,
                    shuffle = true, repeatMode = Player.REPEAT_MODE_ONE,
                ),
                actions, stack(),
            )
        }
        composeRule.onNodeWithText("No Duration").assertIsDisplayed()
        // Position and duration both read 0:00 with no loaded track length.
        composeRule.onAllNodesWithText("0:00").assertCountEquals(2)
    }
}
