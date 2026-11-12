package com.vayunmathur.findfamily.util

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.ui.UwbRangingContent
import com.vayunmathur.findfamily.uwb.UwbCancelReason
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Requester side of #869: a CANCEL whose reason says the peer's phone can't do Find Nearby
 * lands in [UwbSessionManager.UwbSessionState.PeerUnsupported] and renders its own message;
 * every other CANCEL keeps the old "peer stopped" behaviour.
 */
@RunWith(AndroidJUnit4::class)
class UwbPeerDeclineTest {

    @get:Rule val rule = createComposeRule()

    private fun cancel(reason: String?) = UwbEnvelope(
        sessionId = "s",
        sender = 7uL,
        senderPlatform = "android",
        kind = UwbEnvelopeKind.CANCEL,
        reason = reason,
    )

    @Test
    fun unsupportedReasonMapsToPeerUnsupported() {
        assertEquals(
            UwbSessionManager.UwbSessionState.PeerUnsupported,
            UwbSessionManager.stateAfterPeerCancel(cancel(UwbCancelReason.UNSUPPORTED)),
        )
    }

    @Test
    fun plainOrUnknownCancelStaysIdle() {
        // No reason: every older peer, and a user-initiated stop.
        assertEquals(UwbSessionManager.UwbSessionState.Idle, UwbSessionManager.stateAfterPeerCancel(cancel(null)))
        // A reason this build doesn't know yet (newer peer) is a plain cancel too.
        assertEquals(UwbSessionManager.UwbSessionState.Idle, UwbSessionManager.stateAfterPeerCancel(cancel("busy")))
    }

    @Test
    fun peerUnsupportedRendersItsOwnMessage() {
        rule.setContent {
            UwbRangingContent(
                peerName = "Alice",
                session = UwbSessionManager.UwbSessionState.PeerUnsupported,
                onBack = {},
            )
        }
        rule.onNodeWithText("Their phone doesn't support Find Nearby.").assertIsDisplayed()
    }

    @Test
    fun timeoutKeepsTheOldMessage() {
        rule.setContent {
            UwbRangingContent(
                peerName = "Alice",
                session = UwbSessionManager.UwbSessionState.Failed("Peer did not respond"),
                onBack = {},
            )
        }
        rule.onNodeWithText("Find Nearby (UWB) failed.").assertIsDisplayed()
        rule.onNodeWithText("Peer did not respond").assertIsDisplayed()
    }
}
