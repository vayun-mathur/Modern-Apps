package com.vayunmathur.web.ui

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
class TabSwitcherTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        tabs: List<BrowserTab> = listOf(
            BrowserTab(id = "1", url = "https://example.com", title = "Example"),
            BrowserTab(id = "2", url = "https://other.org", title = "Other"),
        ),
        activeTabId: String? = "1",
        onSwitch: (String) -> Unit = {},
        onClose: (String) -> Unit = {},
        onNewTab: () -> Unit = {},
    ) {
        rule.setContent {
            TabSwitcher(
                tabs = tabs,
                activeTabId = activeTabId,
                onSwitch = onSwitch,
                onClose = onClose,
                onNewTab = onNewTab,
                onDismiss = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `tabs render with their titles`() {
        show()
        rule.onNodeWithText("Example").assertIsDisplayed()
        rule.onNodeWithText("Other").assertIsDisplayed()
    }

    @Test
    fun `tapping a tile switches to it`() {
        var switched: String? = null
        show(onSwitch = { switched = it })
        rule.onNodeWithText("Other").performClick()
        rule.waitForIdle()
        assertEquals("2", switched)
    }

    @Test
    fun `the add action opens a new tab`() {
        var opened = false
        show(onNewTab = { opened = true })
        rule.onNodeWithContentDescription("Add").performClick()
        rule.waitForIdle()
        assertTrue(opened)
    }

    @Test
    fun `an empty switcher offers a new tab button`() {
        var opened = false
        rule.setContent {
            TabSwitcher(
                tabs = emptyList(),
                activeTabId = null,
                onSwitch = {},
                onClose = {},
                onNewTab = { opened = true },
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("New tab").performClick()
        rule.waitForIdle()
        assertTrue(opened)
    }
}
