package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.AudioGain
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.FocusArbitration
import com.vayunmathur.auto.protocol.VideoFocus
import com.vayunmathur.auto.protocol.VideoFocusMode
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Auto module: [AutoSessionState] — the session-to-UI bridge. Every event
 * family lands on its flows; socket accept/disconnect resets them.
 */
@RunWith(RobolectricTestRunner::class)
class AutoSessionStateTest {

    @BeforeTest
    fun reset() {
        AutoSessionState.onDisconnected()
        AutoSessionState.onSocketAccepted()
    }

    private fun projectingArbitration(): FocusArbitration =
        FocusArbitration().also {
            it.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
            it.onAudioFocus(AudioFocusState.AUDIO_FOCUS_STATE_GAIN)
        }

    // --- Lifecycle ------------------------------------------------------------

    @Test
    fun `socket accept resets to connecting with cleared counters`() {
        AutoSessionState.onVideoEvent(VideoEvent.FrameSent(1L, 10L))
        AutoSessionState.onCallAdded(ActiveCallInfo(1, "555", 0L))
        AutoSessionState.onSocketAccepted()
        assertEquals(AutoConnectionState.Connecting, AutoSessionState.connection.value)
        assertEquals(0L, AutoSessionState.framesSent.value)
        assertNull(AutoSessionState.activeCall.value)
        assertNull(AutoSessionState.video.value)
    }

    @Test
    fun `active names the car and disconnect returns to idle`() {
        AutoSessionState.onActive("Test Car")
        assertEquals(
            AutoConnectionState.Projecting("Test Car"),
            AutoSessionState.connection.value,
        )
        AutoSessionState.onDisconnected()
        assertEquals(AutoConnectionState.Disconnected, AutoSessionState.connection.value)
    }

    @Test
    fun `rejected marks refusal and session end routes on its failure`() {
        AutoSessionState.onRejected()
        assertEquals(AutoConnectionState.Rejected, AutoSessionState.connection.value)
        AutoSessionState.onSessionEnd("cert")
        assertEquals(AutoConnectionState.Rejected, AutoSessionState.connection.value)
        AutoSessionState.onSessionEnd(null)
        assertEquals(AutoConnectionState.Disconnected, AutoSessionState.connection.value)
    }

    @Test
    fun `credential expiry lands on its flow`() {
        AutoSessionState.onCredentialExpiry(42L)
        assertEquals(42L, AutoSessionState.credentialDaysLeft.value)
    }

    // --- Focus ------------------------------------------------------------------

    @Test
    fun `focus arbitration mirrors into the phone-visible flows`() {
        AutoSessionState.onActive("Car")
        AutoSessionState.onFocusChanged(projectingArbitration())
        assertEquals(VideoFocus.PROJECTED, AutoSessionState.videoFocus.value)
        assertEquals(AudioFocusState.AUDIO_FOCUS_STATE_GAIN, AutoSessionState.audioFocus.value)
        assertTrue(AutoSessionState.inputAllowed.value)
        val projecting = AutoSessionState.connection.value as AutoConnectionState.Projecting
        assertEquals(VideoFocus.PROJECTED, projecting.videoFocus)
        assertEquals("Car", projecting.carName)
    }

    @Test
    fun `focus without a projection does not rewrite the connection`() {
        AutoSessionState.onFocusChanged(projectingArbitration())
        assertEquals(AutoConnectionState.Connecting, AutoSessionState.connection.value)
        assertTrue(AutoSessionState.inputAllowed.value)
    }

    // --- Video --------------------------------------------------------------------

    @Test
    fun `video setup focus frames and surface land`() {
        val info = VideoInfo(720, 480, 30, 1)
        AutoSessionState.onVideoEvent(VideoEvent.Setup(info))
        assertEquals(info, AutoSessionState.video.value)
        AutoSessionState.onVideoEvent(VideoEvent.FocusChanged("VIDEO_FOCUS_NATIVE"))
        assertEquals("VIDEO_FOCUS_NATIVE", AutoSessionState.focusMode.value)
        AutoSessionState.onVideoEvent(VideoEvent.FirstFrame)
        AutoSessionState.onVideoEvent(VideoEvent.FrameSent(100L, 50L))
        AutoSessionState.onVideoEvent(VideoEvent.FrameSent(200L, 70L))
        assertEquals(2L, AutoSessionState.framesSent.value)
        assertEquals(60L, AutoSessionState.avgEncodeLatencyUs.value)
        AutoSessionState.onVideoEvent(VideoEvent.SurfaceChanged(true))
        assertTrue(AutoSessionState.surfaceValid.value)
        AutoSessionState.onVideoEvent(VideoEvent.EncoderDrained(3))
        assertEquals(1L, AutoSessionState.encoderDrains.value)
    }

    @Test
    fun `acks advance the counters and the unwrapped sequence`() {
        AutoSessionState.onVideoEvent(VideoEvent.AckReceived(10L, 0))
        AutoSessionState.onVideoEvent(VideoEvent.AckReceived(11L, 1))
        assertEquals(2L, AutoSessionState.acksSeen.value)
        assertEquals(11L, AutoSessionState.lastAckSeq.value)
        assertTrue((AutoSessionState.lastAckAt.value ?: 0L) > 0L)
        assertEquals(0L, AutoSessionState.lastAckAgeMs.value)
    }

    @Test
    fun `an ack without a sequence still proves liveness`() {
        AutoSessionState.onVideoEvent(VideoEvent.AckReceived(null, 0))
        assertEquals(1L, AutoSessionState.acksSeen.value)
        assertNull(AutoSessionState.lastAckSeq.value)
        assertTrue((AutoSessionState.lastAckAt.value ?: 0L) > 0L)
    }

    @Test
    fun `mismatched acks count separately and still prove liveness`() {
        AutoSessionState.onVideoEvent(VideoEvent.AckMismatch(expected = 1, actual = 2))
        assertEquals(1L, AutoSessionState.acksSeen.value)
        assertEquals(1L, AutoSessionState.ackMismatches.value)
        assertTrue((AutoSessionState.lastAckAt.value ?: 0L) > 0L)
    }

    // --- Calls ----------------------------------------------------------------------

    @Test
    fun `call add change and remove drive the card snapshot`() {
        val ringing = ActiveCallInfo(telecomState = 1, number = "555", acceptedMs = 0L)
        assertTrue(ringing.isIncoming)
        AutoSessionState.onCallAdded(ringing)
        assertEquals(ringing, AutoSessionState.activeCall.value)
        val active = ringing.copy(acceptedMs = 1000L, held = true, muted = true)
        assertFalse(active.isIncoming)
        AutoSessionState.onCallChanged(active)
        assertEquals(active, AutoSessionState.activeCall.value)
        AutoSessionState.onCallRemoved()
        assertNull(AutoSessionState.activeCall.value)
        // Floors at zero, never negative.
        AutoSessionState.onCallRemoved()
        AutoSessionState.onCallAvailability(true)
        assertEquals(true, AutoSessionState.callAvailable.value)
    }

    // --- Media / messaging / input / audio / sensors --------------------------------------

    @Test
    fun `media now-playing lands`() {
        val info = NowPlayingInfo(title = "Song", artist = "Band", playing = true, positionMs = 0L, durationMs = null)
        AutoSessionState.onMediaEvent(MediaEvent.NowPlayingChanged(info))
        assertEquals(info, AutoSessionState.nowPlaying.value)
    }

    @Test
    fun `messaging counts threads messages and replies`() {
        AutoSessionState.onMessagingEvent(MessagingEvent.ThreadsPosted(3))
        AutoSessionState.onMessagingEvent(MessagingEvent.MessagePosted("t1"))
        AutoSessionState.onMessagingEvent(MessagingEvent.ReplyReceived("t1"))
        AutoSessionState.onMessagingEvent(MessagingEvent.ReplySent("t1"))
        AutoSessionState.onMessagingEvent(MessagingEvent.ReplyFailed("t1", "nope"))
        AutoSessionState.onMessagingEvent(MessagingEvent.MarkedRead("t1"))
        assertEquals(3L, AutoSessionState.threadsPosted.value)
        assertEquals(1L, AutoSessionState.messagesPosted.value)
        assertEquals(2L, AutoSessionState.repliesReceived.value)
    }

    @Test
    fun `input touches keys scrolls and drops count`() {
        AutoSessionState.onInputEvent(InputEvent.BindingRequested(2))
        AutoSessionState.onInputEvent(InputEvent.BindingAnswered(0))
        AutoSessionState.onInputEvent(InputEvent.Touch(action = 0, pointerCount = 1))
        AutoSessionState.onInputEvent(InputEvent.Key(keycode = 4, down = true))
        AutoSessionState.onInputEvent(InputEvent.Scroll(delta = 3))
        AutoSessionState.onInputEvent(InputEvent.VolumeKey(keycode = 24, down = true))
        AutoSessionState.onInputEvent(InputEvent.FeedbackSent(1))
        AutoSessionState.onInputEvent(InputEvent.DroppedNoFocus)
        assertEquals(1L, AutoSessionState.touchesInjected.value)
        assertEquals(2L, AutoSessionState.keysInjected.value)
        assertEquals(1L, AutoSessionState.scrollsInjected.value)
        assertEquals(1L, AutoSessionState.inputDropped.value)
    }

    @Test
    fun `audio sinks bytes syncs and music count`() {
        val status = AudioSinkStatus(role = AudioSinkRole.MEDIA, state = AudioSinkState.STARTED, gain = AudioGain.FULL)
        AutoSessionState.onAudioEvent(AudioEvent.SinkSetup("MEDIA", "d"))
        AutoSessionState.onAudioEvent(AudioEvent.SinkStatus(status))
        AutoSessionState.onAudioEvent(AudioEvent.SinkStarted("MEDIA", 0))
        AutoSessionState.onAudioEvent(AudioEvent.SinkStopped("MEDIA"))
        assertEquals(status, AutoSessionState.sinkStatus.value[AudioSinkRole.MEDIA])
        AutoSessionState.onAudioEvent(AudioEvent.FramesSent("MEDIA", 100L))
        AutoSessionState.onAudioEvent(AudioEvent.SyncReceived("MEDIA"))
        AutoSessionState.onAudioEvent(AudioEvent.AckReceived("MEDIA", 1L))
        AutoSessionState.onAudioEvent(AudioEvent.MusicCaptured(50L))
        AutoSessionState.onAudioEvent(AudioEvent.MusicDropped("busy"))
        assertEquals(100L, AutoSessionState.audioBytesSent.value)
        assertEquals(50L, AutoSessionState.musicBytesCaptured.value)
        assertEquals(1L, AutoSessionState.audioSyncs.value)
        assertEquals(1L, AutoSessionState.audioAcks.value)
    }

    @Test
    fun `audio tts and mic turns count`() {
        AutoSessionState.onAudioEvent(AudioEvent.TtsSpoken(10))
        AutoSessionState.onAudioEvent(AudioEvent.TtsDropped("late"))
        AutoSessionState.onAudioEvent(AudioEvent.MicTurn(200L))
        AutoSessionState.onAudioEvent(AudioEvent.MicAcked)
        AutoSessionState.onAudioEvent(AudioEvent.MicIdle)
        assertEquals(1L, AutoSessionState.ttsSpoken.value)
        assertEquals(1L, AutoSessionState.ttsDropped.value)
        assertEquals(1L, AutoSessionState.micTurns.value)
        assertEquals(1L, AutoSessionState.micAcks.value)
    }

    @Test
    fun `sensor guidance and nav posts count`() {
        AutoSessionState.onSensorEvent(SensorEvent.Subscribed("gyro"))
        AutoSessionState.onSensorEvent(SensorEvent.SubscriptionAnswered("gyro", 0))
        AutoSessionState.onSensorEvent(SensorEvent.BatchReceived(5))
        AutoSessionState.onSensorEvent(SensorEvent.SensorError("gyro", 1))
        AutoSessionState.onSensorEvent(SensorEvent.GuidanceSetup)
        AutoSessionState.onSensorEvent(SensorEvent.GuidanceConfigured(true))
        AutoSessionState.onSensorEvent(SensorEvent.NavStatusPosted)
        assertEquals(1L, AutoSessionState.sensorsSubscribed.value)
        assertEquals(1L, AutoSessionState.sensorBatches.value)
        assertEquals(1L, AutoSessionState.sensorErrors.value)
        assertEquals(2L, AutoSessionState.guidanceEvents.value)
        assertEquals(1L, AutoSessionState.navStatusPosts.value)
    }
}
