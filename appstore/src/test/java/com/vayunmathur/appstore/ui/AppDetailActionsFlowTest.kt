package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppDetailActions
import com.vayunmathur.appstore.util.AppDetailUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail-screen action flows: uninstall asks first (the dialog names
 * the app and package) and only the confirm reaches the actions; the
 * dismiss path leaves the installed app installed.
 */
@RunWith(RobolectricTestRunner::class)
class AppDetailActionsFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = UnifiedApp(
        packageName = "com.example.app",
        source = AppSource.FDROID,
        name = "Example",
        versionName = "1.0",
        versionCode = 1L,
    )

    private fun state() = AppDetailUiState(
        app = app(),
        installedInfo = InstalledInfo(
            packageName = "com.example.app",
            name = "Example",
            versionName = "1.0",
            versionCode = 1L,
        ),
    )

    /** The uninstall affordance is an icon-only button: walk the
     *  clickables (back, Open, delete) until the dialog appears. */
    private fun openUninstallDialog() {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            rule.waitForIdle()
            if (rule.onAllNodesWithText("Uninstall Example?").fetchSemanticsNodes().isNotEmpty()) {
                return
            }
        }
        throw AssertionError("uninstall dialog never appeared")
    }

    @Test
    fun `uninstall is confirmed in a dialog before it reaches the actions`() {
        var uninstalled: String? = null
        val actions = object : AppDetailActions {
            override fun uninstallApp(packageName: String) {
                uninstalled = packageName
            }
        }
        rule.setContent { AppDetailScreen(state = state(), actions = actions) }
        openUninstallDialog()
        // The dialog is up; nothing has been uninstalled yet.
        assertNull(uninstalled)
        rule.onNodeWithText("Uninstall Example?").assertIsDisplayed()
        rule.onNodeWithText("Uninstall").performClick()
        assertEquals("com.example.app", uninstalled)
    }

    @Test
    fun `dismissing the uninstall dialog uninstalls nothing`() {
        var uninstalled: String? = null
        val actions = object : AppDetailActions {
            override fun uninstallApp(packageName: String) {
                uninstalled = packageName
            }
        }
        rule.setContent { AppDetailScreen(state = state(), actions = actions) }
        openUninstallDialog()
        rule.onNodeWithText("Cancel").performClick()
        assertNull(uninstalled)
    }
}
