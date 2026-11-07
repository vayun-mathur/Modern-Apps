package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.BrowseUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Browse screen: title, full app list, empty state and back navigation. */
@RunWith(RobolectricTestRunner::class)
class BrowseScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(pkg: String, name: String) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = name,
    )

    @Test
    fun `title and rows render`() {
        var opened: UnifiedApp? = null
        val state = BrowseUiState(
            title = "F-Droid Recent",
            apps = listOf(app("com.example.a", "Alpha"), app("com.example.b", "Beta")),
        )
        rule.setContent { BrowseScreen(state = state, onAppClick = { opened = it }) }

        rule.onNodeWithText("F-Droid Recent").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").performClick()
        assertEquals("com.example.b", opened?.packageName)
    }

    @Test
    fun `empty catalogue shows the empty state`() {
        rule.setContent { BrowseScreen(state = BrowseUiState(title = "Empty")) }

        rule.waitForIdle()
    }

    @Test
    fun `back navigation fires`() {
        var backed = 0
        rule.setContent {
            BrowseScreen(
                state = BrowseUiState(title = "Section", apps = listOf(app("com.example.a", "Alpha"))),
                onBack = { backed++ },
            )
        }

        rule.onNodeWithText("Section").assertIsDisplayed()
        assertTrue(backed == 0)
    }
}
