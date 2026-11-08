package com.vayunmathur.youpipe.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.youpipe.util.SponsorSegment
import com.vayunmathur.youpipe.util.VideoRowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: notification progress bar tap-to-seek and video sections. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProgressSectionsPass3Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun progressBar_tapSeeksToTappedFraction() {
        var sought: Long? = null
        rule.setContent {
            VideoPlayerNotificationProgressBar(
                currentPosition = 10_000, bufferedPosition = 50_000, duration = 100_000,
                isPlaying = true,
                sponsorSegments = listOf(SponsorSegment("sponsor", listOf(10f, 20f), "seg-1")),
                onSeekTo = { sought = it },
            )
        }
        val bounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput {
            click(Offset(bounds.left + bounds.width * 0.75f, bounds.top + bounds.height / 2f))
        }
        val result = sought
        assertTrue(result != null && result in 60_000..90_000, "sought=$result")
    }

    @Test
    fun progressBar_zeroDurationTapDoesNotSeek() {
        var sought: Long? = null
        rule.setContent {
            VideoPlayerNotificationProgressBar(
                currentPosition = 0, bufferedPosition = 0, duration = 0,
                isPlaying = false, sponsorSegments = emptyList(),
                onSeekTo = { sought = it },
            )
        }
        rule.onRoot().performTouchInput { click(center) }
        assertNull(sought)
    }

    @Test
    fun relatedVideos_clickOpensVideo() {
        var opened: Long? = null
        val row = VideoRowState(61, "Related One", "", "Chan", "4 views", null, "chan")
        rule.setContent { RelatedVideosSection(listOf(row)) { opened = it } }
        rule.onNodeWithText("Related One").assertIsDisplayed()
        rule.onNodeWithText("Related One").performClick()
        assertEquals(61L, opened)
    }

    @Test
    fun commentsAndDescription_renderContent() {
        rule.setContent {
            CommentsSection(listOf(Comment("Great video", "A Fan", 12, 1)))
        }
        rule.onNodeWithText("Great video").assertIsDisplayed()
        rule.onNodeWithText("A Fan").assertIsDisplayed()
        rule.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun descriptionSection_rendersText() {
        rule.setContent { DescriptionSection("Hello description world") }
        rule.onNodeWithText("Hello description world", substring = true).assertIsDisplayed()
    }
}
