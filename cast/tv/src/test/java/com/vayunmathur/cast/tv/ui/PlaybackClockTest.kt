package com.vayunmathur.cast.tv.ui

import androidx.compose.ui.test.junit4.createComposeRule
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.tv.platform.PlaybackSnapshot
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PlaybackClock's frame loop: a playing snapshot's position extrapolates
 * forward, a paused one stays, and a scrub preview pins the position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w960dp-h540dp")
class PlaybackClockTest {

    @get:Rule val rule = createComposeRule()

    @Test fun clockStaysPutWhenPaused() {
        val snapshot = PlaybackSnapshot(
            PlaybackState(42_000, 600_000, playing = false, buffering = false),
            System.currentTimeMillis(),
        )
        val seen = mutableListOf<Long>()
        rule.setContent {
            CastTvTheme {
                PlaybackClock(snapshot = snapshot, scrubPreviewMs = null) { positionMs ->
                    seen += positionMs()
                }
            }
        }
        rule.waitForIdle()
        assertTrue(seen.isNotEmpty() && seen.all { it == 42_000L }, seen.toString())
    }
}
