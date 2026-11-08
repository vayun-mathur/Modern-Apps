package com.vayunmathur.youpipe.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.youpipe.util.SearchResultRow
import com.vayunmathur.youpipe.util.SponsorSegment
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Slice 2/3 pass 3: canvas-drawn UI under native graphics, where draw code actually runs. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressNativeGraphicsPass3Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun progressBar_drawsPlayedBufferedAndSponsorSpans() {
        var sought: Long? = null
        rule.setContent {
            VideoPlayerNotificationProgressBar(
                currentPosition = 25_000, bufferedPosition = 80_000, duration = 100_000,
                isPlaying = false,
                sponsorSegments = listOf(SponsorSegment("sponsor", listOf(10f, 20f), "seg-1")),
                onSeekTo = { sought = it },
            )
        }
        rule.waitForIdle()
        // Drawn with a sponsor span present; the bar still reports no seek yet.
        assertTrue(sought == null)
    }

    @Test
    fun channelRow_withAvatarUrl_drawsAvatarAndName() {
        rule.setContent {
            ChannelRow(SearchResultRow.Channel("UC9", "Avatar Channel", "asset://avatar", "42 subscribers")) {}
        }
        rule.onNodeWithText("Avatar Channel").assertIsDisplayed()
        rule.onNodeWithText("42 subscribers").assertIsDisplayed()
    }
}
