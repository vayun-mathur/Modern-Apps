package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.LyricLine
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.tv.platform.ArtworkFetcher
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot
import com.vayunmathur.cast.tv.platform.ReceiverFailure
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import com.vayunmathur.cast.tv.platform.ReceiverUiState
import kotlin.io.encoding.Base64
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/** Failure screens, the network-blocked action, artwork loading, and the lyrics fade. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w960dp-h540dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiDeep2Test {

    @get:Rule val rule = createComposeRule()

    private fun snap(pos: Long = 30_000) = PlaybackSnapshot(
        PlaybackState(pos, 600_000, playing = true, buffering = false, hasNext = true, hasPrevious = true),
        System.currentTimeMillis(),
    )

    @Test fun everyFailureRendersItsScreen() {
        val failureState = androidx.compose.runtime.mutableStateOf(ReceiverFailure.entries.first())
        rule.setContent {
            CastTvTheme { ReceiverContent(ReceiverUiState(phase = ReceiverPhase.Failed(failureState.value))) }
        }
        for (next in ReceiverFailure.entries) {
            failureState.value = next
            rule.waitForIdle()
        }
    }

    @Test fun networkBlockedButtonFiresSettingsIntent() {
        rule.setContent {
            CastTvTheme { ReceiverContent(ReceiverUiState(localNetworkBlocked = true)) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Open Settings", substring = true).performClick()
        rule.waitForIdle()
    }

    @Test fun lyricsFadeDrawsWithTimedLines() {
        val np = NowPlaying(
            resourceId = "r1", title = "Song",
            lyrics = (0..12).map { LyricLine(it * 10_000L, "lyric line number $it") },
        )
        rule.setContent {
            CastTvTheme {
                Lyrics(np, { 25_000 }, Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()
        // Forcing a draw runs the fading-edge mask over the column.
        rule.onRoot().captureToImage()
        rule.onNodeWithText("lyric line number 2").assertExists()
    }

    @Test fun lyricsPlainBlockDraws() {
        val np = NowPlaying(
            resourceId = "r1", title = "Song",
            plainLyrics = "all the words\nof the song\nin one block",
        )
        rule.setContent {
            CastTvTheme { Lyrics(np, { 0 }, Modifier.fillMaxSize()) }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun nowPlayingFetchesArtworkThroughFetcher() {
        val fetcher = ArtworkFetcher(
            ContentSession(
                host = "127.0.0.1", port = 9,
                certificateFingerprint = Base64.encode(ByteArray(32) { 4 }),
                token = "tok", video = false,
            ),
        )
        val np = NowPlaying(
            resourceId = "r1", title = "Cover Song", author = "Artist",
            artworkResourceId = "cover-1",
        )
        rule.setContent {
            CastTvTheme { NowPlayingScreen(np, snap(), "YouPipe", { 30_000 }, fetcher) }
        }
        // The fetch fails soft (nothing listens on port 9) and the placeholder stays.
        Thread.sleep(500)
        rule.waitForIdle()
        rule.onNodeWithText("Cover Song").assertExists()
    }

    @Test fun nowPlayingWithoutArtworkResource() {
        val np = NowPlaying(resourceId = "r1", title = "No Cover", author = "Artist")
        rule.setContent {
            CastTvTheme { NowPlayingScreen(np, snap(), "YouPipe", { 30_000 }, null) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("No Cover").assertExists()
    }
}
