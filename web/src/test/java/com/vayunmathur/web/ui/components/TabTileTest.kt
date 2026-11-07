package com.vayunmathur.web.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.web.platform.BrowserTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TabTileTest {

    @get:Rule
    val rule = createComposeRule()

    // ---------------------------------------------------------- SiteIcon

    @Test
    fun `an icon bitmap renders the image`() {
        val icon = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        rule.setContent {
            SiteIcon(icon = icon, label = "Example")
        }
        rule.waitForIdle()
        // No initial letter when a real icon is present.
        assertTrue(
            rule.onAllNodes(androidx.compose.ui.test.hasText("E"))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `a missing icon falls back to the initial`() {
        rule.setContent {
            SiteIcon(icon = null, label = "Example")
        }
        rule.waitForIdle()
        rule.onNodeWithText("E").assertIsDisplayed()
    }

    @Test
    fun `a blank label falls back to the globe`() {
        rule.setContent {
            SiteIcon(icon = null, label = "   ")
        }
        rule.waitForIdle()
        // Globe icon rendered, no text node.
        assertTrue(
            rule.onAllNodes(androidx.compose.ui.test.hasText(" ", substring = true))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // ---------------------------------------------------------- TabTile

    private fun tile(
        tab: BrowserTab = BrowserTab(id = "1", url = "https://example.com", title = "Example"),
        onSwitch: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        rule.setContent {
            TabTile(
                tab = tab,
                isActive = true,
                thumbnail = null,
                favicon = null,
                onSwitch = onSwitch,
                onClose = onClose,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `the tile shows the tab title`() {
        tile()
        rule.onNodeWithText("Example").assertIsDisplayed()
    }

    @Test
    fun `a blank tab shows the new-tab label`() {
        tile(tab = BrowserTab(id = "1", url = ""))
        rule.onNodeWithText("New tab", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a private tab shows the private marker`() {
        tile(
            tab = BrowserTab(
                id = "1",
                url = "https://example.com",
                title = "X",
                isPrivate = true,
            )
        )
        // Private prefix text is rendered alongside the title.
        rule.onNodeWithText("Private", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tapping the tile switches`() {
        var switched = false
        tile(onSwitch = { switched = true })
        rule.onNodeWithText("Example").performClick()
        rule.waitForIdle()
        assertTrue(switched)
    }

    @Test
    fun `a search tab shows the query`() {
        tile(
            tab = BrowserTab(
                id = "1",
                url = "search://results?q=cats",
                title = "",
            )
        )
        rule.onNodeWithText("cats", substring = true).assertIsDisplayed()
    }
}
