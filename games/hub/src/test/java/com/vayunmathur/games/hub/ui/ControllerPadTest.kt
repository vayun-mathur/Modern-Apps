package com.vayunmathur.games.hub.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.games.hub.ui.screens.ControllerPad
import com.vayunmathur.games.hub.ui.screens.ControllerPadState
import com.vayunmathur.sdk.controller.ClientState
import com.vayunmathur.sdk.controller.ControllerHostInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Play on TV" pad: screen stays on while seated; copy comes from resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControllerPadTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Pad(state: ControllerPadState) {
        ControllerPad(
            state = state,
            onConnect = {}, onProveCode = {}, onJoin = {},
            onInput = { _, _, _, _ -> }, onLeave = {}, onRescan = {},
        )
    }

    @Test
    fun screenStaysOnOnlyWhileSeatedAtThePad() {
        var state by mutableStateOf(ControllerPadState(clientState = ClientState.IDLE))
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            Pad(state)
        }
        rule.waitForIdle()
        assertFalse(view.keepScreenOn, "searching for a TV should not pin the screen on")

        state = ControllerPadState(clientState = ClientState.JOINED)
        rule.waitForIdle()
        assertTrue(view.keepScreenOn, "the pad must keep the screen awake while driving")
        rule.onNodeWithContentDescription("Up").assertIsDisplayed()

        state = ControllerPadState(clientState = ClientState.IDLE)
        rule.waitForIdle()
        assertFalse(view.keepScreenOn, "leaving the pad hands the setting back")
    }

    @Test
    fun discoveryCopyIsResourceBackedAndNamesTheGame() {
        val host = ControllerHostInfo(
            id = "tv1", instanceName = "tv1", friendlyName = "Living room TV",
            host = "10.0.0.2", port = 1, protocolVersion = 1, gameId = "kart",
        )
        rule.setContent { Pad(ControllerPadState(clientState = ClientState.FAILED, hosts = listOf(host))) }
        rule.onNodeWithText("Living room TV").assertIsDisplayed()
        rule.onNodeWithText("Hosting Kart").assertIsDisplayed()
        rule.onNodeWithText("Couldn't reach that TV", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Connect").assertIsDisplayed()
        rule.onNodeWithText("Rescan").assertIsDisplayed()
    }
}
