package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.cast.protocol.LyricLine
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.tv.Route
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot
import com.vayunmathur.cast.tv.platform.ReceiverFailure
import com.vayunmathur.cast.tv.platform.ReceiverUiState

/** 1920x1080-class TV: 960x540dp at 320dpi (the panel size Android TVs report). */
private const val TV = "spec:width=960dp,height=540dp,dpi=320"

private val fixtureState = ReceiverUiState(
    deviceName = "Living Room TV",
    networkLabel = "HomeWi-Fi · 192.168.1.4",
)

private val fixtureNowPlaying = NowPlaying(
    title = "Midnight Run",
    author = "The Night Trains",
    album = "Northbound",
    lyrics = listOf(
        LyricLine(atMs = 0L, text = "City lights are fading"),
        LyricLine(atMs = 5_000L, text = "Northbound on the midnight run"),
        LyricLine(atMs = 10_000L, text = "Harbour lights behind us"),
    ),
)

private val fixturePlayback = PlaybackSnapshot(
    state = PlaybackState(positionMs = 62_000L, durationMs = 214_000L, playing = true, buffering = false, hasNext = true, hasPrevious = true),
    receivedAtMs = 0L,
)

/**
 * Store listing images for `:cast:tv` in the `tv` bucket — every top-level receiver
 * screen (starting, ready, pairing, connected, failed, network-blocked) plus the
 * audio now-playing screen and the transport overlay.
 *
 * Exception (honest, not faked): the mirroring picture itself is a decoded video
 * surface (`MirrorActivity` + decoder), which Layoutlib cannot render; it is not
 * previewed and no synthetic frame is invented. The overlay that sits over it is
 * captured over a plain dark background.
 * `./gradlew :cast:tv:metadata` copies renders into `metadata_data/photos/cast-tv/tv/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-starting", device = TV, group = "tv")
    @Composable
    fun Preview1Starting() {
        CastTvTheme { ReceiverContent(route = Route.Starting, state = fixtureState) }
    }

    @PreviewTest
    @Preview(name = "2-ready", device = TV, group = "tv")
    @Composable
    fun Preview2Ready() {
        CastTvTheme { ReceiverContent(route = Route.Ready, state = fixtureState) }
    }

    @PreviewTest
    @Preview(name = "3-pairing", device = TV, group = "tv")
    @Composable
    fun Preview3Pairing() {
        CastTvTheme {
            ReceiverContent(route = Route.Pairing(senderName = "Pixel 9", code = "123456", attemptsLeft = 3), state = fixtureState)
        }
    }

    @PreviewTest
    @Preview(name = "4-connected", device = TV, group = "tv")
    @Composable
    fun Preview4Connected() {
        CastTvTheme { ReceiverContent(route = Route.Connected(senderName = "Pixel 9"), state = fixtureState) }
    }

    @PreviewTest
    @Preview(name = "5-failed", device = TV, group = "tv")
    @Composable
    fun Preview5Failed() {
        CastTvTheme { ReceiverContent(route = Route.Failed(reason = ReceiverFailure.StreamEnded), state = fixtureState) }
    }

    @PreviewTest
    @Preview(name = "6-network-blocked", device = TV, group = "tv")
    @Composable
    fun Preview6NetworkBlocked() {
        CastTvTheme {
            ReceiverContent(route = Route.Ready, state = fixtureState.copy(localNetworkBlocked = true))
        }
    }

    @PreviewTest
    @Preview(name = "7-now-playing", device = TV, group = "tv")
    @Composable
    fun Preview7NowPlaying() {
        CastTvTheme {
            Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                NowPlayingScreen(nowPlaying = fixtureNowPlaying, snapshot = fixturePlayback, sourceName = "Music", positionMs = { 62_000L }, artwork = null)
            }
        }
    }

    @PreviewTest
    @Preview(name = "8-transport-overlay", device = TV, group = "tv")
    @Composable
    fun Preview8TransportOverlay() {
        CastTvTheme {
            Box(Modifier.fillMaxSize().background(Color(0xFF202830))) {
                TransportOverlay(snapshot = fixturePlayback, sourceName = "Music", nowPlaying = fixtureNowPlaying, positionMs = { 62_000L }, scrubbing = false)
            }
        }
    }
}
