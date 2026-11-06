package com.vayunmathur.communicate.googlevoice

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.CallState
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallBridge
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the Google Voice call state ([CallState]/[CallPhase])
 * and the synchronous [GoogleVoiceCallManager] controls.
 *
 * Robolectric (the manager touches `Log` + app context). Only deterministic,
 * network-free behavior is asserted — mute/speaker flags, no-op guards, and
 * state reset — the async SIP/WebRTC leg needs a live registrar.
 */
@RunWith(RobolectricTestRunner::class)
class GoogleVoiceCallStateSlice4Test {

    @Test
    fun `call state defaults to idle`() {
        val state = CallState()
        assertEquals(CallPhase.Idle, state.phase)
        assertEquals("", state.remoteNumber)
        assertFalse(state.muted)
        assertFalse(state.speaker)
    }

    @Test
    fun `manager starts idle and clears terminal state`() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        GoogleVoiceCallManager.init(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
        )
        assertEquals(CallPhase.Idle, GoogleVoiceCallManager.state.value.phase)
        // Nothing terminal: clear is a no-op that keeps Idle.
        GoogleVoiceCallManager.clearEnded()
        assertEquals(CallPhase.Idle, GoogleVoiceCallManager.state.value.phase)
    }

    @Test
    fun `mute and speaker update state without audio`() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        GoogleVoiceCallManager.init(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
        )
        GoogleVoiceCallManager.setMuted(true)
        assertTrue(GoogleVoiceCallManager.state.value.muted)
        GoogleVoiceCallManager.setMuted(false)
        assertFalse(GoogleVoiceCallManager.state.value.muted)
        GoogleVoiceCallManager.setSpeaker(true)
        assertTrue(GoogleVoiceCallManager.state.value.speaker)
        GoogleVoiceCallManager.setSpeaker(false)
        assertFalse(GoogleVoiceCallManager.state.value.speaker)
    }

    @Test
    fun `dtmf without audio is a safe no-op`() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        GoogleVoiceCallManager.init(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
        )
        GoogleVoiceCallManager.sendDtmf("5")
    }

    @Test
    fun `stop registration resets to idle`() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        GoogleVoiceCallManager.init(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
        )
        GoogleVoiceCallManager.stopRegistration()
        assertEquals(CallState(), GoogleVoiceCallManager.state.value)
    }

    @Test
    fun `bridge start is idempotent`() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        GoogleVoiceCallManager.init(context)
        GoogleVoiceCallBridge.ensureStarted()
        GoogleVoiceCallBridge.ensureStarted()
        GoogleVoiceCallManager.stopRegistration()
        com.vayunmathur.communicate.data.call.InAppCallRegistry.onEnded("reset")
        com.vayunmathur.communicate.data.call.InAppCallRegistry.clearEnded()
    }
}
