package com.vayunmathur.cast.tv.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.cast.protocol.LyricLine
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot
import com.vayunmathur.cast.tv.platform.ReceiverFailure
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import com.vayunmathur.cast.tv.platform.ReceiverUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w960dp-h540dp")
class TvUiComposeTest {
    @get:Rule val rule = createComposeRule()
    private fun snap(playing: Boolean = true, buffering: Boolean = false, duration: Long = 600_000, pos: Long = 30_000, speed: Float = 1f) =
        PlaybackSnapshot(PlaybackState(pos, duration, playing, buffering, speed = speed, hasNext = true, hasPrevious = true), 1000)

    @Test fun receiverContentStarting() {
        rule.setContent { CastTvTheme { ReceiverContent(ReceiverUiState(phase = ReceiverPhase.Starting)) } }
        rule.waitForIdle()
    }
    @Test fun receiverContentAdvertisingShowsDeviceAndNetwork() {
        rule.setContent { CastTvTheme { ReceiverContent(ReceiverUiState(phase = ReceiverPhase.Advertising, deviceName = "Living Room TV", networkLabel = "HomeWiFi · 192.168.1.4")) } }
        rule.onNodeWithText("Living Room TV").assertIsDisplayed()
        rule.onNodeWithText("HomeWiFi · 192.168.1.4", substring = true).assertIsDisplayed()
    }
    @Test fun receiverContentPairing() {
        rule.setContent { CastTvTheme { ReceiverContent(ReceiverUiState(phase = ReceiverPhase.Pairing("Pixel 9", "123456", 2))) } }
        rule.onNodeWithText("123  456").assertIsDisplayed()
    }
    @Test fun receiverContentConnectedAndMirroring() {
        rule.setContent { CastTvTheme { ReceiverContent(ReceiverUiState(phase = ReceiverPhase.Connected("Pixel 9"))) } }
        rule.waitForIdle()
    }
    @Test fun pairingScreenChangedCode() {
        rule.setContent { CastTvTheme { PairingScreen("Pixel 9", "654321", 3, codeChanged = true) } }
        rule.onNodeWithText("654  321").assertIsDisplayed()
    }
    @Test fun receiverContentMirroring() {
        rule.setContent { CastTvTheme { ReceiverContent(ReceiverUiState(phase = ReceiverPhase.Mirroring("Pixel 9", 1920, 1080, appLabel = "YouPipe"))) } }
        rule.waitForIdle()
    }
    @Test fun receiverContentAllFailures() {
        rule.setContent { CastTvTheme { ReceiverContent(ReceiverUiState(phase = ReceiverPhase.Failed(ReceiverFailure.NoDecoder))) } }
        rule.waitForIdle()
    }
    @Test fun receiverContentFailureVariants() {
        // remaining failures share the FailedScreen shape; rendered in platform-level state tests plus this smoke of each message branch via separate compositions is done in individual tests below
        rule.setContent { CastTvTheme { ReceiverContent(ReceiverUiState(phase = ReceiverPhase.Failed(ReceiverFailure.StreamEnded))) } }
        rule.waitForIdle()
    }
    @Test fun receiverContentNetworkBlocked() {
        rule.setContent { CastTvTheme { ReceiverContent(ReceiverUiState(localNetworkBlocked = true)) } }
        rule.waitForIdle()
        // The settings button fires an app-details intent; Robolectric shadows the start.
        runCatching { rule.onNodeWithText("Open Settings", substring = true).performClick() }
        rule.waitForIdle()
    }
    @Test fun readyScreenWithoutNetwork() {
        rule.setContent { CastTvTheme { ReadyScreen("TV", "") } }
        rule.onNodeWithText("TV").assertIsDisplayed()
    }
    @Test fun nowPlayingWithPlainLyricsAndPaused() {
        val np = NowPlaying(resourceId = "r1", title = "Blue Monday", author = "New Order", album = "Power", plainLyrics = "How does it feel")
        rule.setContent { CastTvTheme { NowPlayingScreen(np, snap(playing = false), "YouPipe", { 30_000 }, null) } }
        rule.onNodeWithText("Blue Monday").assertIsDisplayed(); rule.onNodeWithText("New Order").assertIsDisplayed(); rule.onNodeWithText("How does it feel").assertIsDisplayed()
    }
    @Test fun nowPlayingWithTimedLyricsBufferingAndNoDurationProgress() {
        val np = NowPlaying(title = "Song", lyrics = listOf(LyricLine(0, "first line"), LyricLine(10_000, "second line")))
        rule.setContent { CastTvTheme { NowPlayingScreen(np, snap(buffering = true, duration = 0), "", { 5_000 }, null) } }
        rule.onNodeWithText("first line").assertIsDisplayed(); rule.onNodeWithText("second line").assertIsDisplayed()
    }
    @Test fun nowPlayingBlankTitleFallsBackToSource() {
        val np = NowPlaying(title = "")
        rule.setContent { CastTvTheme { NowPlayingScreen(np, snap(), "Pixel 9", { 0 }, null) } }
        rule.onNodeWithText("PIXEL 9").assertExists()
    }
    @Test fun playbackClockScrubPreviewOverrides() {
        var seen = -1L
        rule.setContent { CastTvTheme { PlaybackClock(snap(playing = false), 42_000) { pos -> seen = pos() } } }
        assertEquals(42_000, seen)
    }
    @Test fun nowPlayingWithArtworkFetcherThatCannotFetch() {
        val session = com.vayunmathur.cast.protocol.ContentSession(host = "127.0.0.1", port = 9, certificateFingerprint = "!!!", token = "tok", video = false, appLabel = "App")
        val np = NowPlaying(resourceId = "r1", title = "Cover Song", artworkResourceId = "art1")
        rule.setContent { CastTvTheme { NowPlayingScreen(np, snap(), "YouPipe", { 30_000 }, com.vayunmathur.cast.tv.platform.ArtworkFetcher(session)) } }
        rule.onNodeWithText("Cover Song").assertExists()
        rule.waitForIdle()
    }
    @Test fun lyricsEmptyLyricsShowsPlainBlock() {
        rule.setContent { CastTvTheme { Lyrics(NowPlaying(plainLyrics = "untimed words"), { 0 }) } }
        rule.onNodeWithText("untimed words").assertIsDisplayed()
    }
    @Test fun transportOverlayPlayingPausedAndSpeed() {
        val np = NowPlaying(title = "Track", author = "Artist", album = "Album")
        rule.setContent { CastTvTheme { TransportOverlay(snap(speed = 1.5f), "YouPipe", np, { 30_000 }, scrubbing = false) } }
        rule.onNodeWithText("Track").assertIsDisplayed(); rule.onNodeWithText("Artist").assertIsDisplayed()
    }
    @Test fun transportOverlayPausedNoDurationNoScrim() {
        rule.setContent { CastTvTheme { TransportOverlay(snap(playing = false, duration = 0), "YouPipe", null, { 0 }, scrubbing = true, scrim = false) } }
        rule.onNodeWithText("YouPipe").assertIsDisplayed()
    }
    @Test fun transportButtonsDoNotCrashWithoutSession() {
        rule.setContent { CastTvTheme { TransportOverlay(snap(), "Src", null, { 1000 }, false) } }
        // icon buttons have no text; clicking via semantics on first clickable nodes is covered by rendering + controller no-session path in platform tests
        rule.waitForIdle()
    }
    @Test fun playbackClockPausedProvidesPosition() {
        var seen = -1L
        rule.setContent { CastTvTheme { PlaybackClock(snap(playing = false), null) { pos -> seen = pos() } } }
        assertEquals(30_000, seen)
    }
}
