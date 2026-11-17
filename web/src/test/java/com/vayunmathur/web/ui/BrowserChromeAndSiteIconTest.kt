package com.vayunmathur.web.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.vayunmathur.web.ui.components.SiteIcon
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserChromeAndSiteIconTest {

    @get:Rule val rule = createComposeRule()

    @Test fun chromeShowsUrlTabCountAndHandlesClicks() {
        var omnibox = false; var switcher = false; var menu = false
        rule.setContent {
            BrowserChrome(
                omniboxText = "https://example.com/page",
                tabCount = 3,
                progress = 0.5f,
                onOmniboxClick = { omnibox = true },
                onTabSwitcherClick = { switcher = true },
                onMenuClick = { menu = true },
            ) { padding -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(padding)) {} }
        }
        rule.waitForIdle()
        rule.onNodeWithText("3").assertIsDisplayed()
        rule.onNodeWithText("3").performClick()
        rule.onNodeWithContentDescription("More").performClick()
        rule.waitForIdle()
        assertTrue(switcher)
        assertTrue(menu)
    }

    @Test fun chromeAtBottomAlsoRenders() {
        rule.setContent {
            BrowserChrome(
                omniboxText = "",
                tabCount = 1,
                progress = 0f,
                atBottom = true,
            ) { padding -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(padding)) {} }
        }
        rule.waitForIdle()
        rule.onNodeWithText("1").assertIsDisplayed()
        rule.onNodeWithText("Search or enter address").assertIsDisplayed()
    }

    @Test fun omniboxHeightMatchesTabCountDiameter() {
        rule.setContent {
            BrowserChrome(
                omniboxText = "https://example.com/page",
                tabCount = 12,
                progress = 0f,
            ) { padding -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(padding)) {} }
        }
        rule.waitForIdle()
        val tabBounds = rule.onNodeWithText("12").fetchSemanticsNode().boundsInRoot
        val fieldBounds = rule.onNodeWithText("https://example.com/page").fetchSemanticsNode().boundsInRoot
        // Same height on both controls, and the tab indicator is a square
        // (circle) even with a two-digit count.
        kotlin.test.assertEquals(tabBounds.height, fieldBounds.height)
        kotlin.test.assertEquals(tabBounds.width, tabBounds.height)
        // Vanadium-proportioned bar: tall enough for bodyMedium text
        // (20 sp line height) plus the outlined field's 12+12 dp of
        // vertical content padding, i.e. comfortably above the old
        // 36 dp height that clipped its own text.
        kotlin.test.assertEquals(48.dp, ChromeControlHeight)
    }

    @Test fun siteIconShowsInitialForLabel() {
        rule.setContent { SiteIcon(icon = null, label = "example") }
        rule.waitForIdle()
        rule.onNodeWithText("E").assertIsDisplayed()
    }

    @Test fun siteIconWithBitmapRendersImage() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        rule.setContent { SiteIcon(icon = bmp, label = "example") }
        rule.waitForIdle()
        // No initial letter is drawn when an icon exists.
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("E")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun siteIconBlankLabelShowsGlobeFallback() {
        rule.setContent { SiteIcon(icon = null, label = "   ") }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText(" ")).fetchSemanticsNodes().isEmpty())
    }
}
