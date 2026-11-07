package com.vayunmathur.uniflix.ui

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.domain.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Watch overlay bars + player-state holder with no real player attached. */
@RunWith(RobolectricTestRunner::class)
class WatchControlsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun playerState(): WatchPlayerState = WatchPlayerState(
        player = null,
        positionState = mutableLongStateOf(65_000L),
        bufferedState = mutableLongStateOf(80_000L),
        durationState = mutableLongStateOf(3_723_000L),
        playingState = mutableStateOf(false),
        bufferingState = mutableStateOf(true),
        draggingState = mutableStateOf(false),
        cuesState = mutableStateOf(emptyList()),
        heightsState = mutableStateOf(listOf(1080, 720)),
        errorState = mutableStateOf<String?>(null),
    )

    @Test fun playerStateWithoutPlayerIsInert() {
        val state = playerState()
        state.togglePlay()
        state.seekTo(-5_000L)
        state.skipBy(10_000L)
        assertEquals(65_000L, state.positionMs)
        assertEquals(3_723_000L, state.durationMs)
        assertEquals(true, state.isBuffering)
        assertEquals(listOf(1080, 720), state.videoHeights)
        assertNull(state.playerError)
        state.playerError = "boom"
        assertEquals("boom", state.playerError)
    }

    @Test fun subtitleMimeVariants() {
        assertEquals("application/x-subrip", subtitleMime("https://x/en.srt"))
        assertEquals("application/ttml+xml", subtitleMime("https://x/en.ttml"))
        assertEquals("application/ttml+xml", subtitleMime("https://x/en.dfxp?tok=1"))
        assertEquals("text/vtt", subtitleMime("https://x/en.vtt"))
    }

    @Test fun topBarShowsChipsAndSelectsHeight() {
        var height: Int? = 720
        var menuOpen: Boolean? = null
        var backed = 0
        val subs = listOf(SubtitleTrack("en", "English", "https://x/en.vtt"))
        composeRule.setContent {
            DynamicTheme {
                WatchTopBar(
                    title = "A Title",
                    serverLabel = "Alpha",
                    servers = listOf("Alpha", "Beta"),
                    onSelectServer = {},
                    videoHeights = listOf(2160, 1080),
                    selectedHeight = height,
                    onSelectHeight = { height = it },
                    subtitles = subs,
                    selectedSubtitle = null,
                    onSelectSubtitle = {},
                    onBack = { backed++ },
                    onAnyMenuOpen = { menuOpen = it })
            }
        }
        composeRule.onNodeWithText("A Title").assertIsDisplayed()
        composeRule.onNodeWithText("720p").assertIsDisplayed()
        composeRule.onNodeWithText("CC").assertIsDisplayed()
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("720p").performClick()
        composeRule.onNodeWithText("4K").assertIsDisplayed()
        composeRule.onNodeWithText("4K").performClick()
        assertEquals(2160, height)
        assertEquals(0, backed)
    }

    @Test fun bottomBarShowsTimes() {
        val state = playerState()
        var fullscreen: Boolean? = null
        composeRule.setContent {
            DynamicTheme {
                WatchBottomBar(
                    playerState = state,
                    isFullscreen = false,
                    onFullscreenChange = { fullscreen = it })
            }
        }
        composeRule.onNodeWithText("1:05").assertIsDisplayed()
        composeRule.onNodeWithText("1:02:03").assertIsDisplayed()
        assertEquals(null, fullscreen)
        assertEquals(80_000L, state.bufferedMs)
    }
}
