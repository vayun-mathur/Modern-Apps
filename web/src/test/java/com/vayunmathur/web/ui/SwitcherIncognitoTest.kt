package com.vayunmathur.web.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.vayunmathur.web.platform.BrowserTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SwitcherIncognitoTest {

    @get:Rule val rule = createComposeRule()

    @Test fun incognitoSwitcherOffersIncognitoTabAndWindows() {
        var newIncognitoTab = false
        var newWindow = false
        var newIncognitoWindow = false
        rule.setContent {
            TabSwitcher(
                tabs = listOf(BrowserTab(id = "p1", url = "https://priv.example", title = "Priv", isPrivate = true)),
                activeTabId = "p1",
                onSwitch = {},
                onClose = {},
                onNewTab = {},
                onNewIncognitoTab = { newIncognitoTab = true },
                onNewWindow = { newWindow = true },
                onNewIncognitoWindow = { newIncognitoWindow = true },
                onDismiss = {},
                isIncognitoWindow = true,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Priv").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("New Incognito Window"))
        rule.onNodeWithText("New Incognito Window").assertIsDisplayed()
        rule.onNodeWithText("New Incognito Window").performClick()
        rule.waitForIdle()
        assertTrue(newIncognitoWindow)
        rule.onNodeWithText("New Window").performClick()
        rule.waitForIdle()
        assertTrue(newWindow)
        // The main button in an incognito window creates another incognito tab.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("New Incognito Tab"))
        rule.onNodeWithText("New Incognito Tab").performClick()
        rule.waitForIdle()
        assertEquals(true, newIncognitoTab || newWindow)
    }

    @Test fun switcherDismissAndAddButtons() {
        var dismissed = false
        var added = false
        rule.setContent {
            TabSwitcher(
                tabs = listOf(BrowserTab(id = "1", url = "https://a.example", title = "A")),
                activeTabId = "1",
                onSwitch = {},
                onClose = {},
                onNewTab = { added = true },
                onDismiss = { dismissed = true },
            )
        }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Add")).performClick()
        rule.waitForIdle()
        assertTrue(added)
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close"))[0].performClick()
        rule.waitForIdle()
        assertTrue(dismissed)
    }
}
