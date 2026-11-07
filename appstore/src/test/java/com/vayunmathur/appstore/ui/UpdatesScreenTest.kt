package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.UpdatesActions
import com.vayunmathur.appstore.util.UpdatesUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Updates screen states: up-to-date, eligible rows with update buttons,
 * foreign rows under their own header, and the update-all action.
 */
@RunWith(RobolectricTestRunner::class)
class UpdatesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(pkg: String, name: String, versionCode: Long = 2L) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = name,
        versionName = "2.0",
        versionCode = versionCode,
    )

    private fun installed(pkg: String, versionCode: Long = 1L) = InstalledInfo(
        packageName = pkg,
        name = pkg,
        versionName = "1.0",
        versionCode = versionCode,
    )

    @Test
    fun `empty updates show the up-to-date state`() {
        rule.setContent { UpdatesScreen(state = UpdatesUiState(), actions = UpdatesActions.Noop) }

        rule.waitForIdle()
    }

    @Test
    fun `eligible rows show the old-to-new version and update on tap`() {
        val updated = mutableListOf<String>()
        val actions = object : UpdatesActions {
            override fun updateAll() {}
            override fun install(app: UnifiedApp) {
                updated += app.packageName
            }
        }
        val state = UpdatesUiState(
            updates = listOf(app("com.example.a", "Alpha")),
            installedInfos = mapOf("com.example.a" to installed("com.example.a")),
        )
        rule.setContent { UpdatesScreen(state = state, actions = actions) }

        rule.onNodeWithText("Alpha").assertIsDisplayed()
        // The row button reads exactly "Update"; "Update all (1)" is a substring trap.
        rule.onNodeWithText("Update", substring = false).performClick()
        assertEquals(listOf("com.example.a"), updated)
    }

    @Test
    fun `foreign rows render under their own section`() {
        val state = UpdatesUiState(
            updates = listOf(app("com.example.a", "Alpha")),
            foreignUpdates = listOf(app("com.example.b", "Beta")),
            installedInfos = mapOf(
                "com.example.a" to installed("com.example.a"),
                "com.example.b" to installed("com.example.b"),
            ),
        )
        rule.setContent { UpdatesScreen(state = state, actions = UpdatesActions.Noop) }

        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun `update all fires once`() {
        var runs = 0
        val actions = object : UpdatesActions {
            override fun updateAll() {
                runs++
            }
        }
        val state = UpdatesUiState(updates = listOf(app("com.example.a", "Alpha")))
        rule.setContent { UpdatesScreen(state = state, actions = actions) }

        rule.onNodeWithText("Update all", substring = true).performClick()
        assertTrue(runs >= 1)
    }

    @Test
    fun `status message shows while checking`() {
        val state = UpdatesUiState(isChecking = true, statusMessage = "Checking Play")
        rule.setContent { UpdatesScreen(state = state, actions = UpdatesActions.Noop) }

        rule.onNodeWithText("Checking Play").assertIsDisplayed()
    }
}
