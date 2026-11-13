package com.vayunmathur.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.Achievement
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The unlock toast: how long it stays, what dismisses it, and what it lets through. */
@RunWith(RobolectricTestRunner::class)
class AchievementNotificationTest {

    @get:Rule
    val rule = createComposeRule()

    private val first = Achievement(
        id = "a1", name = "First win", description = "Win once", targetProgress = 1, isSecret = false,
    )

    private var dismissed = 0
    private var behindTaps = 0

    private fun setToastOverScreen(achievement: Achievement = first) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize().testTag(ROOT)) {
                Box(Modifier.fillMaxSize().clickable { behindTaps++ })
                AchievementNotification(achievement = achievement, onDismiss = { dismissed++ })
            }
        }
        rule.mainClock.advanceTimeBy(ENTER_SETTLED)
    }

    @Test
    fun `the toast stays about four seconds, not eight`() {
        setToastOverScreen()
        rule.mainClock.advanceTimeBy(3_000 - ENTER_SETTLED)
        assertEquals(0, dismissed, "still up at three seconds")
        rule.mainClock.advanceTimeBy(2_500)
        rule.waitForIdle()
        assertEquals(1, dismissed, "gone, exit animation included, by five and a half seconds")
    }

    @Test
    fun `a tap on the card dismisses it early`() {
        setToastOverScreen()
        rule.onNodeWithText("First win").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(0, behindTaps, "the tap belonged to the card")
    }

    @Test
    fun `a tap outside the card passes through to the screen`() {
        setToastOverScreen()
        rule.onNodeWithTag(ROOT).performTouchInput { click(Offset(centerX, height - 4f)) }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        assertEquals(1, behindTaps)
        assertEquals(0, dismissed)
    }

    @Test
    fun `queued unlocks each get a full toast, in order`() {
        val second = first.copy(id = "a2", name = "Ten wins", description = "Win ten times")
        val queue = mutableStateListOf(first, second)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            queue.firstOrNull()?.let { AchievementNotification(achievement = it, onDismiss = { queue.removeAt(0) }) }
        }
        rule.mainClock.advanceTimeBy(ENTER_SETTLED)
        rule.onNodeWithText("First win").assertIsDisplayed()
        rule.onNodeWithText("Ten wins").assertDoesNotExist()

        rule.mainClock.advanceTimeBy(5_500)
        rule.waitForIdle()
        rule.onNodeWithText("First win").assertDoesNotExist()
        rule.onNodeWithText("Ten wins").assertIsDisplayed()
        // The second only started once the first had timed out (t >= 4 s), so it is still up at 7.5 s:
        // it got its own full display rather than inheriting the first one's exit.
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Ten wins").assertIsDisplayed()

        rule.mainClock.advanceTimeBy(4_000)
        rule.waitForIdle()
        assertEquals(0, queue.size)
    }

    @Test
    fun `the toast keeps clear of the status bar inset`() {
        var insets by mutableStateOf(WindowInsets(0.dp))
        rule.mainClock.autoAdvance = false
        rule.setContent { AchievementNotification(achievement = first, onDismiss = {}, windowInsets = insets) }
        rule.mainClock.advanceTimeBy(ENTER_SETTLED)
        val flush = rule.onNodeWithText("First win").getUnclippedBoundsInRoot().top

        insets = WindowInsets(top = 40.dp)
        rule.mainClock.advanceTimeBy(100)
        val inset = rule.onNodeWithText("First win").getUnclippedBoundsInRoot().top
        assertEquals(40f, (inset - flush).value, 0.5f, "the whole card moves down by the inset")
    }

    private companion object {
        const val ROOT = "root"

        /** Past the slide-in, so the card is in place and hit-testable. */
        const val ENTER_SETTLED = 1_000L
    }
}
