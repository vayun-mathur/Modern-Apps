package com.vayunmathur.communicate.ui.call

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.call.InAppCallState
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the shared call-screen controls (`ui/call/`).
 *
 * Robolectric + `createComposeRule`: renders the header, incoming actions,
 * capability-gated ongoing controls and the DTMF keypad, and asserts taps fan
 * out to the registry — the contract that keeps inert controls off screen.
 */
@RunWith(RobolectricTestRunner::class)
class InAppCallControlsSlice4Test {

    @get:Rule
    val rule = createComposeRule()

    private val actions = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val controller = object : InAppCallController {
        override fun answer() { actions.add("answer") }
        override fun reject() { actions.add("reject") }
        override fun hangup() { actions.add("hangup") }
        override fun setMuted(muted: Boolean) { actions.add("muted=$muted") }
        override fun setSpeaker(on: Boolean) { actions.add("speaker=$on") }
    }

    @BeforeTest
    fun setUp() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        actions.clear()
        InAppCallRegistry.onEnded("reset")
        InAppCallRegistry.clearEnded()
        InAppCallRegistry.bind(CommunicateLine.Sim, controller)
        InAppCallRegistry.videoController = null
        InAppCallRegistry.dtmfController = null
        InAppCallRegistry.holdController = null
        InAppCallRegistry.connection = null
    }

    private fun outgoingState() = InAppCallState(
        phase = InAppCallPhase.Outgoing,
        line = CommunicateLine.Sim,
        peerId = "+1555",
        peerName = "Pat Lee",
        capabilities = CallCapabilities.AudioOnly,
    )

    @Test
    fun `header shows monogram name and dialing status`() {
        rule.setContent { CallHeader(state = outgoingState(), showMonogram = true) }
        rule.onNodeWithText("PL").assertIsDisplayed()
        rule.onNodeWithText("Pat Lee").assertIsDisplayed()
        rule.onNodeWithText("Dialing…").assertIsDisplayed()
    }

    @Test
    fun `header without monogram still names the peer`() {
        val incoming = outgoingState().copy(phase = InAppCallPhase.Incoming)
        rule.setContent { CallHeader(state = incoming, showMonogram = false) }
        rule.onNodeWithText("Pat Lee").assertIsDisplayed()
        rule.onNodeWithText("Incoming call - SIM").assertIsDisplayed()
    }

    @Test
    fun `incoming controls answer and decline`() {
        rule.setContent { IncomingControls() }
        // The text labels sit beside (not inside) the clickable action circles,
        // so click the action whose icon carries the matching description.
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("End call")).performClick()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Call")).performClick()
        rule.waitForIdle()
        kotlin.test.assertEquals(listOf("reject", "answer"), actions.toList())
    }

    @Test
    fun `ongoing audio-only shows mute speaker and end`() {
        rule.setContent {
            OngoingControls(
                state = outgoingState(),
                keypadShown = false,
                onToggleKeypad = {},
                onToggleScreenShare = {},
            )
        }
        rule.onNodeWithText("Mute").assertIsDisplayed()
        rule.onNodeWithText("Speaker").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("End call")).performClick()
        rule.waitForIdle()
        kotlin.test.assertEquals(listOf("hangup"), actions.toList())
    }

    @Test
    fun `keypad toggle is absent without the dtmf capability`() {
        rule.setContent {
            OngoingControls(
                state = outgoingState(),
                keypadShown = false,
                onToggleKeypad = {},
                onToggleScreenShare = {},
            )
        }
        rule.onNodeWithText("Keypad").assertDoesNotExist()
    }

    @Test
    fun `keypad toggle fires with the dtmf capability`() {
        val toggles = java.util.Collections.synchronizedList(mutableListOf<Unit>())
        rule.setContent {
            OngoingControls(
                state = outgoingState().copy(capabilities = CallCapabilities.AudioAndKeypad),
                keypadShown = false,
                onToggleKeypad = { toggles.add(Unit) },
                onToggleScreenShare = {},
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Keypad")).performClick()
        rule.waitForIdle()
        kotlin.test.assertEquals(1, toggles.size)
    }

    @Test
    fun `dtmf keypad emits every digit`() {
        val digits = java.util.Collections.synchronizedList(mutableListOf<String>())
        rule.setContent { DtmfKeypad(onDigit = { digits.add(it) }) }
        for (d in listOf("1", "5", "9", "*", "0", "#")) {
            rule.onNodeWithText(d).performClick()
        }
        rule.waitForIdle()
        kotlin.test.assertEquals(listOf("1", "5", "9", "*", "0", "#"), digits.toList())
    }

    @Test
    fun `video capability shows video and hides keypad`() {
        rule.setContent {
            OngoingControls(
                state = outgoingState().copy(capabilities = CallCapabilities.AudioAndVideo),
                keypadShown = false,
                onToggleKeypad = {},
                onToggleScreenShare = {},
            )
        }
        rule.onNodeWithText("Video").assertIsDisplayed()
        rule.onNodeWithText("Keypad").assertDoesNotExist()
    }

    @Test
    fun `held call reports on hold`() {
        rule.setContent {
            CallHeader(
                state = outgoingState().copy(phase = InAppCallPhase.Active, held = true),
                showMonogram = true,
            )
        }
        rule.onNodeWithText("On hold").assertIsDisplayed()
    }

    @Test
    fun `status text names the phase and line`() {
        fun status(phase: InAppCallPhase, line: CommunicateLine = CommunicateLine.Sim, held: Boolean = false) =
            outgoingState().copy(phase = phase, line = line, held = held)
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                CallHeader(state = status(InAppCallPhase.Connecting), showMonogram = false)
                CallHeader(state = status(InAppCallPhase.Active), showMonogram = false)
                CallHeader(state = status(InAppCallPhase.Ended), showMonogram = false)
            }
        }
        rule.onNodeWithText("Connecting…").assertIsDisplayed()
        // Active + unheld names the line; Ended without a reason uses the default.
        rule.onNodeWithText("SIM").assertIsDisplayed()
        rule.onNodeWithText("Call ended").assertIsDisplayed()
    }

    @Test
    fun `ended call with a reason shows the reason`() {
        rule.setContent {
            CallHeader(
                state = outgoingState().copy(phase = InAppCallPhase.Ended, endReason = "Declined"),
                showMonogram = false,
            )
        }
        rule.onNodeWithText("Declined").assertIsDisplayed()
    }

    @Test
    fun `active call ticks its duration`() {
        // connectedAtMs in the past: the duration subtree appears under the header.
        rule.setContent {
            CallHeader(
                state = outgoingState().copy(
                    phase = InAppCallPhase.Active,
                    connectedAtMs = System.currentTimeMillis() - 61_000L,
                ),
                showMonogram = false,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("1:01").assertIsDisplayed()
    }
}
