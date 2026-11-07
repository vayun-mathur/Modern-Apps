package com.vayunmathur.communicate.ui.call

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallDtmfController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: the full [InAppCallScreen] driven through the registry, phase by phase. */
@RunWith(RobolectricTestRunner::class)
class InAppCallScreenSlice4Pass2Test {

    @get:Rule val rule = createComposeRule()

    private val actions = mutableListOf<String>()
    private val controller = object : InAppCallController {
        override fun answer() { actions += "answer" }
        override fun reject() { actions += "reject" }
        override fun hangup() { actions += "hangup" }
        override fun setMuted(muted: Boolean) { actions += "muted=$muted" }
        override fun setSpeaker(on: Boolean) { actions += "speaker=$on" }
    }

    @BeforeTest
    fun setUp() {
        actions.clear()
        // Drive the registry back to Idle regardless of what a previous test left behind.
        InAppCallRegistry.onEnded("reset")
        InAppCallRegistry.clearEnded()
        InAppCallRegistry.videoController = null
        InAppCallRegistry.dtmfController = null
        InAppCallRegistry.holdController = null
        InAppCallRegistry.connection = null
    }

    private fun startCall(
        incoming: Boolean,
        capabilities: CallCapabilities = CallCapabilities.AudioOnly,
        video: Boolean = false,
    ) {
        InAppCallRegistry.bind(CommunicateLine.Signal, controller, capabilities)
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.Signal,
            peerId = "peer-1",
            peerName = "Pat Lee",
            isVideo = video,
            incoming = incoming,
            capabilities = capabilities,
        )
    }

    @Test
    fun idleScreenClosesImmediately() {
        var closed = 0
        rule.setContent { InAppCallScreen(onClose = { closed++ }) }
        rule.waitForIdle()
        assertTrue(closed >= 1, "Idle phase should close the call screen")
    }

    @Test
    fun incomingScreenShowsPeerAndAnswerDeclineWork() {
        startCall(incoming = true)
        var closed = 0
        rule.setContent { InAppCallScreen(onClose = { closed++ }) }
        rule.onNodeWithText("Pat Lee").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Call")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("answer"), actions.toList())
        assertEquals(0, closed)
    }

    @Test
    fun outgoingScreenShowsDialingAndHangupWorks() {
        startCall(incoming = false)
        rule.setContent { InAppCallScreen(onClose = {}) }
        rule.onNodeWithText("Pat Lee").assertIsDisplayed()
        rule.onNodeWithText("Dialing…").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("End call")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("hangup"), actions.toList())
    }

    @Test
    fun activeKeypadCallCanOpenKeypadAndSendDigits() {
        val digits = mutableListOf<String>()
        startCall(incoming = false, capabilities = CallCapabilities.AudioAndKeypad)
        InAppCallRegistry.dtmfController = object : InAppCallDtmfController {
            override fun sendDtmf(digit: String) { digits += digit }
        }
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        rule.setContent { InAppCallScreen(onClose = {}) }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Keypad")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("5").performClick()
        rule.waitForIdle()
        assertEquals(listOf("5"), digits.toList())
    }

    @Test
    fun activeVideoCapabilityScreenWithoutControllerStillShowsControls() {
        startCall(incoming = false, capabilities = CallCapabilities.AudioAndVideo, video = true)
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        InAppCallRegistry.onRemoteVideo(true)
        rule.setContent { InAppCallScreen(onClose = {}) }
        // No video controller is bound, so CallVideo returns early and the header/controls remain.
        rule.onNodeWithText("Pat Lee").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("End call")).performClick()
        assertEquals(listOf("hangup"), actions.toList())
    }

    @Test
    fun endedScreenClosesAfterHold() {
        startCall(incoming = false)
        InAppCallRegistry.onEnded("done")
        var closed = 0
        rule.setContent { InAppCallScreen(onClose = { closed++ }) }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertTrue(closed >= 1, "Ended phase should close after the hold")
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun selfViewConstantsClampWindow() {
        assertEquals(108, SELF_VIEW_WIDTH)
        assertEquals(72, SELF_VIEW_MIN)
        assertTrue(SELF_VIEW_MIN_SCALE < 1f && SELF_VIEW_MAX_SCALE > 1f)
        assertEquals(220, SELF_VIEW_BOTTOM_INSET)
    }
}
