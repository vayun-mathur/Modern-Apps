package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/** Transport overlay shapes: playing, paused, buffering, ended, scrubbing, live. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w960dp-h540dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayDeepTest {

    @get:Rule val rule = createComposeRule()

    private fun snapshot(state: PlaybackState) = PlaybackSnapshot(state, System.currentTimeMillis())

    @Test fun overlayVariantsRender() {
        val variants = listOf(
            PlaybackState(30_000, 600_000, playing = true, buffering = false, hasNext = true, hasPrevious = true),
            PlaybackState(30_000, 600_000, playing = false, buffering = false),
            PlaybackState(30_000, 600_000, playing = false, buffering = true),
            PlaybackState(600_000, 600_000, playing = false, buffering = false, ended = true),
            PlaybackState(10_000, 0, playing = true, buffering = false), // live: no duration
        )
        val stateHolder = androidx.compose.runtime.mutableStateOf(snapshot(variants.first()))
        rule.setContent {
            CastTvTheme {
                TransportOverlay(
                    snapshot = stateHolder.value,
                    sourceName = "YouPipe",
                    nowPlaying = NowPlaying(resourceId = "r1", title = "Clip", author = "Channel"),
                    positionMs = { 30_000 },
                    scrubbing = false,
                    scrim = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        for (variant in variants) {
            stateHolder.value = snapshot(variant)
            rule.waitForIdle()
        }
        rule.onRoot().captureToImage()
    }

    @Test fun overlayWhileScrubbing() {
        rule.setContent {
            CastTvTheme {
                TransportOverlay(
                    snapshot = snapshot(PlaybackState(30_000, 600_000, playing = true, buffering = false)),
                    sourceName = "YouPipe",
                    nowPlaying = null,
                    positionMs = { 45_000 },
                    scrubbing = true,
                    scrim = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

}
