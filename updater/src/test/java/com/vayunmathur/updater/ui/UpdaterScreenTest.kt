package com.vayunmathur.updater.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.updater.domain.UpdateMetadata
import com.vayunmathur.updater.platform.UpdaterUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdaterScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setScreen(
        state: UpdaterUiState,
        onCheck: () -> Unit = {},
        onAuto: (Boolean) -> Unit = {},
        onMetered: (Boolean) -> Unit = {},
    ) {
        rule.setContent {
            DynamicTheme {
                UpdaterScreen(
                    state = state,
                    onCheckNow = onCheck,
                    onAutoInstallChanged = onAuto,
                    onMeteredAllowedChanged = onMetered,
                )
            }
        }
    }

    @Test
    fun `fresh state says not checked yet and shows the unknown build`() {
        setScreen(UpdaterUiState())
        rule.onNodeWithText("Not checked yet").assertIsDisplayed()
        rule.onNodeWithText("Current build unknown").assertIsDisplayed()
        rule.onNodeWithText("Check now").assertIsDisplayed()
    }

    @Test
    fun `a checked device with nothing newer says up to date with its build`() {
        setScreen(UpdaterUiState(currentBuild = "2026090500", lastCheckedMillis = 1234L))
        rule.onNodeWithText("Your system is up to date").assertIsDisplayed()
        rule.onNodeWithText("Current build 2026090500").assertIsDisplayed()
    }

    @Test
    fun `an available update names the target build`() {
        setScreen(
            UpdaterUiState(
                currentBuild = "2026090500",
                available = UpdateMetadata("2026090700", 2000L),
                lastCheckedMillis = 1234L,
            ),
        )
        rule.onNodeWithText("Update available: 2026090700").assertIsDisplayed()
    }

    @Test
    fun `checking replaces the button with progress`() {
        setScreen(UpdaterUiState(currentBuild = "2026090500", checking = true))
        rule.onNodeWithText("Checking for updates…").assertIsDisplayed()
        rule.onNodeWithText("Check now").assertDoesNotExist()
    }

    @Test
    fun `a failure shows its reason verbatim`() {
        setScreen(
            UpdaterUiState(
                currentBuild = "2026090500",
                lastFailure = "could not reach https://ota.example/shiba-stable",
            ),
        )
        rule.onNodeWithText("Could not check for updates").assertIsDisplayed()
        rule.onNodeWithText("could not reach https://ota.example/shiba-stable").assertIsDisplayed()
    }

    @Test
    fun `check now invokes its callback`() {
        var checked = false
        setScreen(UpdaterUiState(currentBuild = "2026090500"), onCheck = { checked = true })
        rule.onNodeWithText("Check now").performClick()
        assertTrue(checked)
    }

    @Test
    fun `switches show their stored positions`() {
        setScreen(UpdaterUiState(currentBuild = "2026090500", autoInstall = true, meteredAllowed = false))
        rule.onNodeWithText("Install updates automatically").assertIsDisplayed()
        rule.onNodeWithText("Use mobile data").assertIsDisplayed()
        val switches = rule.onAllNodes(androidx.compose.ui.test.isToggleable())
        switches[0].assertIsOn()
        switches[1].assertIsOff()
    }

    @Test
    fun `toggling switches reports the new values`() {
        var auto: Boolean? = null
        var metered: Boolean? = null
        setScreen(
            UpdaterUiState(currentBuild = "2026090500", autoInstall = true, meteredAllowed = false),
            onAuto = { auto = it },
            onMetered = { metered = it },
        )
        val switches = rule.onAllNodes(androidx.compose.ui.test.isToggleable())
        switches[1].performClick()
        assertEquals(true, metered)
        switches[0].performClick()
        assertEquals(false, auto)
    }
}
