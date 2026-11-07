package com.vayunmathur.auto.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.R as AutoR
import com.vayunmathur.auto.platform.AutoConnectionState
import com.vayunmathur.auto.protocol.VideoFocus
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AutoScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun snapshot() = SessionSnapshot(
        video = null, focusMode = null, videoFocus = VideoFocus.NONE,
        audioFocus = AudioFocusState.AUDIO_FOCUS_STATE_INVALID, inputAllowed = false,
        framesSent = 0, acksSeen = 0, ackMismatches = 0, encodedFps = 0.0, ackFps = 0.0,
        lastAckAt = null, lastAckSeq = null, avgEncodeLatencyUs = null, encoderDrains = 0,
        surfaceValid = false, credentialDaysLeft = null, sessionStartedAt = null, nowPlaying = null,
        threadsPosted = 0, messagesPosted = 0, repliesReceived = 0, sinkStatus = emptyMap(),
        audioBytesSent = 0, micTurns = 0, micAcks = 0, ttsSpoken = 0, touchesInjected = 0,
        keysInjected = 0, scrollsInjected = 0, inputDropped = 0, musicBytesCaptured = 0,
    )

    @Test fun `disconnected screen shows hint and routes pairing and pinned taps`() {
        var pairing = 0; var pinned = 0
        rule.setContent {
            AutoScreen(state = AutoConnectionState.Disconnected, session = snapshot(),
                onPairing = { pairing++ }, onPinnedApps = { pinned++ })
        }
        rule.onNodeWithText(ctx.getString(AutoR.string.state_disconnected)).assertExists()
        rule.onNodeWithText(ctx.getString(AutoR.string.connect_hint)).assertExists()
        rule.onNodeWithText(ctx.getString(AutoR.string.pairing_open)).performClick()
        assertEquals(1, pairing)
        // Two nodes carry the pinned-apps title (headline + button); clicking the button is via second
        rule.onAllNodes(androidx.compose.ui.test.hasText(ctx.getString(AutoR.string.pinned_apps_title)))[1].performClick()
        assertEquals(1, pinned)
    }

    @Test fun `projecting screen names the car`() {
        rule.setContent {
            AutoScreen(state = AutoConnectionState.Projecting("Pioneer"), session = snapshot(),
                onPairing = {}, onPinnedApps = {})
        }
        rule.onNodeWithText(ctx.getString(AutoR.string.state_projecting, "Pioneer")).assertExists()
    }

    @Test fun `connecting state renders its label`() {
        rule.setContent {
            AutoScreen(state = AutoConnectionState.Connecting, session = snapshot(),
                onPairing = {}, onPinnedApps = {})
        }
        rule.onNodeWithText(ctx.getString(AutoR.string.state_connecting)).assertExists()
    }

    @Test fun `rejected state renders its label`() {
        rule.setContent {
            AutoScreen(state = AutoConnectionState.Rejected, session = snapshot(),
                onPairing = {}, onPinnedApps = {})
        }
        rule.onNodeWithText(ctx.getString(AutoR.string.state_rejected)).assertExists()
    }
}
