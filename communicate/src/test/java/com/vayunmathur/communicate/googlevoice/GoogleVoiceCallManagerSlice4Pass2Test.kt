package com.vayunmathur.communicate.googlevoice

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: GV call manager state transitions that need no live SIP/WebRTC leg. */
@RunWith(RobolectricTestRunner::class)
class GoogleVoiceCallManagerSlice4Pass2Test {

    @Test
    fun muteAndSpeakerUpdateStateWithoutAudioSession() {
        GoogleVoiceCallManager.stopRegistration()
        assertEquals(CallPhase.Idle, GoogleVoiceCallManager.state.value.phase)
        GoogleVoiceCallManager.setMuted(true)
        assertEquals(true, GoogleVoiceCallManager.state.value.muted)
        GoogleVoiceCallManager.setSpeaker(true)
        assertEquals(true, GoogleVoiceCallManager.state.value.speaker)
        GoogleVoiceCallManager.setMuted(false)
        GoogleVoiceCallManager.setSpeaker(false)
        assertFalse(GoogleVoiceCallManager.state.value.muted)
        assertFalse(GoogleVoiceCallManager.state.value.speaker)
        // DTMF with no audio session is a no-op (must not throw)
        GoogleVoiceCallManager.sendDtmf("5")
    }

    @Test
    fun actionsBeforeInitAreSafeAndStopResetsState() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        GoogleVoiceCallManager.init(ctx)
        GoogleVoiceCallManager.init(ctx) // idempotent
        GoogleVoiceCallManager.setMuted(true)
        GoogleVoiceCallManager.stopRegistration()
        val state = GoogleVoiceCallManager.state.value
        assertEquals(CallPhase.Idle, state.phase)
        assertFalse(state.muted)
        // Clear-ended on an idle state keeps it idle
        GoogleVoiceCallManager.clearEnded()
        assertEquals(CallPhase.Idle, GoogleVoiceCallManager.state.value.phase)
    }
}
