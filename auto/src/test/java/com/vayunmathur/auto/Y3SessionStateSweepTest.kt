package com.vayunmathur.auto

import com.vayunmathur.auto.platform.AudioEvent
import com.vayunmathur.auto.platform.AudioSinkState
import com.vayunmathur.auto.platform.AudioSinkStatus
import com.vayunmathur.auto.platform.AutoSessionState
import com.vayunmathur.auto.platform.InputEvent
import com.vayunmathur.auto.platform.MediaEvent
import com.vayunmathur.auto.platform.MessagingEvent
import com.vayunmathur.auto.platform.NowPlayingInfo
import com.vayunmathur.auto.platform.SensorEvent
import com.vayunmathur.auto.platform.VideoEvent
import com.vayunmathur.auto.platform.VideoInfo
import com.vayunmathur.auto.protocol.AudioSinkRole
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class Y3SessionStateSweepTest {
    @Test fun everyEventVariantReducesIntoState() {
        AutoSessionState.onDisconnected()
        AutoSessionState.onSocketAccepted()
        AutoSessionState.onActive("Test Car")
        AutoSessionState.onVideoEvent(VideoEvent.Setup(VideoInfo(1280, 720, 30, 0)))
        AutoSessionState.onVideoEvent(VideoEvent.FocusChanged("VIDEO_FOCUS_PROJECTED"))
        AutoSessionState.onVideoEvent(VideoEvent.FirstFrame)
        repeat(3) { AutoSessionState.onVideoEvent(VideoEvent.FrameSent(1_000L + it, 100L)) }
        AutoSessionState.onVideoEvent(VideoEvent.AckReceived(7L, 1))
        AutoSessionState.onVideoEvent(VideoEvent.AckMismatch(-1, 42))
        AutoSessionState.onVideoEvent(VideoEvent.EncoderDrained(2))
        AutoSessionState.onVideoEvent(VideoEvent.SurfaceChanged(true))
        assertEquals(1280, AutoSessionState.video.value?.width)
        assertTrue(AutoSessionState.framesSent.value >= 3)

        AutoSessionState.onMediaEvent(MediaEvent.NowPlayingChanged(NowPlayingInfo("S", "A", true, 0, 100, null, null, false, true)))
        AutoSessionState.onMessagingEvent(MessagingEvent.ThreadsPosted(4))
        AutoSessionState.onMessagingEvent(MessagingEvent.MessagePosted("t"))
        AutoSessionState.onMessagingEvent(MessagingEvent.ReplyReceived("t"))
        AutoSessionState.onMessagingEvent(MessagingEvent.ReplySent("t"))
        AutoSessionState.onMessagingEvent(MessagingEvent.ReplyFailed("t", "x"))
        AutoSessionState.onMessagingEvent(MessagingEvent.MarkedRead("t"))
        assertEquals(4L, AutoSessionState.threadsPosted.value)

        AutoSessionState.onInputEvent(InputEvent.BindingRequested(1))
        AutoSessionState.onInputEvent(InputEvent.BindingAnswered(0))
        AutoSessionState.onInputEvent(InputEvent.Touch(0, 1))
        AutoSessionState.onInputEvent(InputEvent.Key(4, true))
        AutoSessionState.onInputEvent(InputEvent.Scroll(1))
        AutoSessionState.onInputEvent(InputEvent.VolumeKey(24, true))
        AutoSessionState.onInputEvent(InputEvent.FeedbackSent(1))
        AutoSessionState.onInputEvent(InputEvent.DroppedNoFocus)

        AutoSessionState.onAudioEvent(AudioEvent.SinkSetup("SYSTEM", "ok"))
        AutoSessionState.onAudioEvent(AudioEvent.SinkStatus(AudioSinkStatus(AudioSinkRole.SYSTEM, AudioSinkState.STARTED, com.vayunmathur.auto.protocol.AudioGain.FULL)))
        AutoSessionState.onAudioEvent(AudioEvent.SinkStarted("SYSTEM", 0))
        AutoSessionState.onAudioEvent(AudioEvent.SinkStopped("SYSTEM"))
        AutoSessionState.onAudioEvent(AudioEvent.MicTurn(128L))
        AutoSessionState.onAudioEvent(AudioEvent.MicAcked)
        AutoSessionState.onAudioEvent(AudioEvent.MicIdle)
        AutoSessionState.onAudioEvent(AudioEvent.FramesSent("SYSTEM", 100L))
        AutoSessionState.onAudioEvent(AudioEvent.SyncReceived("SYSTEM"))
        AutoSessionState.onAudioEvent(AudioEvent.AckReceived("SYSTEM", 3L))
        AutoSessionState.onAudioEvent(AudioEvent.TtsSpoken(10))
        AutoSessionState.onAudioEvent(AudioEvent.TtsDropped("x"))
        AutoSessionState.onAudioEvent(AudioEvent.MusicCaptured(500))
        AutoSessionState.onAudioEvent(AudioEvent.MusicDropped("muted"))

        AutoSessionState.onSensorEvent(SensorEvent.Subscribed("SPEED"))
        AutoSessionState.onSensorEvent(SensorEvent.SubscriptionAnswered("SPEED", 0))
        AutoSessionState.onSensorEvent(SensorEvent.BatchReceived(2))
        AutoSessionState.onSensorEvent(SensorEvent.SensorError("SPEED", -1))
        AutoSessionState.onSensorEvent(SensorEvent.GuidanceSetup)
        AutoSessionState.onSensorEvent(SensorEvent.GuidanceConfigured(true))
        AutoSessionState.onSensorEvent(SensorEvent.NavStatusPosted)

        AutoSessionState.onCallAvailability(true)
        AutoSessionState.onCredentialExpiry(30L)
        AutoSessionState.onSessionEnd(null)
        AutoSessionState.onSessionEnd("refused")
    }

    @Test fun activeRouteValueSemantics() {
        val a = com.vayunmathur.auto.platform.ActiveRoute(
            points = listOf(com.vayunmathur.auto.protocol.MapRoutePoint(1.0, 2.0)),
            nextRoad = "Market", nextTurnDistanceM = 100, maneuver = "left",
        )
        val b = a.copy(nextRoad = "Other")
        assertEquals("Market", a.nextRoad); assertEquals("Other", b.nextRoad)
        assertTrue(a.toString().contains("Market")); assertEquals(a.hashCode(), a.copy().hashCode())
        assertEquals(a, a.copy()); assertTrue(a != b)
        val (points, road, dist, maneuver) = a
        assertEquals(1, points.size); assertEquals(100, dist)
    }
}
