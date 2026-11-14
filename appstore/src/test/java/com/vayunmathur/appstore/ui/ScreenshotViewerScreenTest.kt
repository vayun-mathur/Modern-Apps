package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Full-screen screenshot viewer (#918): starts at the tapped screenshot, swipes between
 * them, describes each page, and closes from its button. Images never decode here (no
 * network loader in unit tests), which is exactly the case the page-level descriptions
 * exist for.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotViewerScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val urls = listOf(
        "https://example.org/1.png",
        "https://example.org/2.png",
        "https://example.org/3.png",
    )

    private fun show(initialIndex: Int = 0, onClose: () -> Unit = {}) {
        rule.setContent {
            ScreenshotViewerScreen(
                appName = "Example",
                urls = urls,
                initialIndex = initialIndex,
                onClose = onClose,
            )
        }
    }

    @Test
    fun `opens at the tapped screenshot`() {
        show(initialIndex = 1)
        rule.onNodeWithText("2 / 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Example screenshot 2 of 3").assertIsDisplayed()
    }

    @Test
    fun `out of range index is clamped`() {
        show(initialIndex = 9)
        rule.onNodeWithText("3 / 3").assertIsDisplayed()
    }

    @Test
    fun `swiping moves to the next screenshot`() {
        show(initialIndex = 0)
        rule.onNodeWithTag(VIEWER_PAGER_TAG).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("2 / 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Example screenshot 2 of 3").assertIsDisplayed()
    }

    @Test
    fun `close button closes`() {
        var closed = 0
        show(onClose = { closed++ })
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `tap hides and restores the chrome`() {
        show()
        val page = rule.onNodeWithContentDescription("Example screenshot 1 of 3")
        page.performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(DOUBLE_TAP_WAIT_MS)
        rule.waitForIdle()
        rule.onNodeWithText("1 / 3").assertDoesNotExist()

        page.performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(DOUBLE_TAP_WAIT_MS)
        rule.waitForIdle()
        rule.onNodeWithText("1 / 3").assertIsDisplayed()
    }

    @Test
    fun `no screenshots closes straight away`() {
        var closed = 0
        rule.setContent {
            ScreenshotViewerScreen(appName = "Example", urls = emptyList(), initialIndex = 0, onClose = { closed++ })
        }
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    private companion object {
        /** Past the double-tap timeout, so a single tap is delivered as one. */
        const val DOUBLE_TAP_WAIT_MS = 1_000L
    }
}
