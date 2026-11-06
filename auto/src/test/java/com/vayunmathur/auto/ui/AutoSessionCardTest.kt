package com.vayunmathur.auto.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.R as AutoR
import com.vayunmathur.auto.platform.AudioSinkState
import com.vayunmathur.auto.platform.AudioSinkStatus
import com.vayunmathur.auto.platform.NowPlayingInfo
import com.vayunmathur.auto.platform.VideoInfo
import com.vayunmathur.auto.protocol.AudioGain
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.VideoFocus
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

/**
 * Auto module: [SessionCard] — the projection session status rows over a
 * [SessionSnapshot]. A bare snapshot shows dashes; a live one shows its
 * video, focus, counts and media rows.
 */
@RunWith(RobolectricTestRunner::class)
class AutoSessionCardTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun bareSnapshot() = SessionSnapshot(
        video = null,
        focusMode = null,
        videoFocus = VideoFocus.NONE,
        audioFocus = AudioFocusState.AUDIO_FOCUS_STATE_INVALID,
        inputAllowed = false,
        framesSent = 0L,
        acksSeen = 0L,
        ackMismatches = 0L,
        encodedFps = 0.0,
        ackFps = 0.0,
        lastAckAt = null,
        lastAckSeq = null,
        avgEncodeLatencyUs = null,
        encoderDrains = 0L,
        surfaceValid = false,
        credentialDaysLeft = null,
        sessionStartedAt = null,
        nowPlaying = null,
        threadsPosted = 0L,
        messagesPosted = 0L,
        repliesReceived = 0L,
        sinkStatus = emptyMap(),
        audioBytesSent = 0L,
        micTurns = 0L,
        micAcks = 0L,
        ttsSpoken = 0L,
        touchesInjected = 0L,
        keysInjected = 0L,
        scrollsInjected = 0L,
        inputDropped = 0L,
        musicBytesCaptured = 0L,
    )

    private fun liveSnapshot() = bareSnapshot().copy(
        video = VideoInfo(width = 720, height = 480, frameRate = 30, configIndex = 1),
        focusMode = "VIDEO_FOCUS_PROJECTED",
        videoFocus = VideoFocus.PROJECTED,
        audioFocus = AudioFocusState.AUDIO_FOCUS_STATE_GAIN,
        inputAllowed = true,
        framesSent = 100L,
        acksSeen = 99L,
        ackMismatches = 2L,
        encodedFps = 30.0,
        ackFps = 29.5,
        lastAckAt = System.currentTimeMillis(),
        lastAckSeq = 77L,
        avgEncodeLatencyUs = 1500L,
        encoderDrains = 4L,
        surfaceValid = true,
        credentialDaysLeft = 5L,
        sessionStartedAt = System.currentTimeMillis() - 65_000L,
        nowPlaying = NowPlayingInfo(
            title = "Test Song",
            artist = "Test Band",
            playing = true,
            positionMs = 0L,
            durationMs = null,
        ),
        threadsPosted = 3L,
        messagesPosted = 7L,
        repliesReceived = 2L,
        sinkStatus = mapOf(
            AudioSinkRole.SYSTEM to AudioSinkStatus(AudioSinkRole.SYSTEM, AudioSinkState.STARTED, AudioGain.FULL),
            AudioSinkRole.MEDIA to AudioSinkStatus(AudioSinkRole.MEDIA, AudioSinkState.IDLE, AudioGain.FULL),
        ),
        audioBytesSent = 4096L,
        micTurns = 3L,
        micAcks = 2L,
        ttsSpoken = 1L,
        touchesInjected = 10L,
        keysInjected = 4L,
        scrollsInjected = 2L,
        inputDropped = 1L,
        musicBytesCaptured = 2048L,
    )

    @Test
    fun `a bare session shows dashes and no video`() {
        rule.setContent { SessionCard(session = bareSnapshot()) }
        assertSingleText(appContext.getString(AutoR.string.session_headline))
        assertSingleText(appContext.getString(AutoR.string.session_video_none))
        // focus_none ("Unknown") shows twice: the wire-mode row and the
        // arbitrated NONE row.
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.session_focus_none))).assertCountEquals(2)
        assertSingleText(appContext.getString(AutoR.string.session_now_playing_none))
        assertSingleText(appContext.getString(AutoR.string.session_credential_unknown))
        // value_none ("—") shows three times: ack seq, latency, elapsed.
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.session_value_none))).assertCountEquals(3)
    }

    @Test
    fun `a live session shows video focus and counts`() {
        rule.setContent { SessionCard(session = liveSnapshot()) }
        val video = appContext.getString(AutoR.string.session_video, 720, 480, 30, 1)
        assertSingleText(video)
        assertSingleText(appContext.getString(AutoR.string.session_focus_projected))
        assertSingleText("VIDEO_FOCUS_PROJECTED")
        assertSingleText(appContext.getString(AutoR.string.session_input_allowed_yes))
        val frames = appContext.getString(AutoR.string.session_frames_value, 100L, 99L)
        assertSingleText(frames)
        // Mismatch + dropped-input rows only render when their counts are positive.
        assertSingleText(appContext.getString(AutoR.string.session_ack_mismatch))
        assertSingleText(appContext.getString(AutoR.string.session_input_dropped))
        assertSingleText(appContext.getString(AutoR.string.session_surface_valid))
    }

    @Test
    fun `now playing renders track artist and state`() {
        rule.setContent { SessionCard(session = liveSnapshot()) }
        val line = appContext.getString(
            AutoR.string.session_now_playing_value,
            "Test Song",
            "Test Band",
            appContext.getString(AutoR.string.session_now_playing_playing),
        )
        assertSingleText(line)
    }

    @Test
    fun `unknown artists fall back to placeholders`() {
        val snapshot = liveSnapshot().copy(
            nowPlaying = NowPlayingInfo(title = null, artist = null, playing = false, positionMs = 0L, durationMs = null),
        )
        rule.setContent { SessionCard(session = snapshot) }
        val line = appContext.getString(
            AutoR.string.session_now_playing_value,
            appContext.getString(AutoR.string.session_now_playing_unknown),
            appContext.getString(AutoR.string.session_now_playing_unknown_artist),
            appContext.getString(AutoR.string.session_now_playing_paused),
        )
        assertSingleText(line)
    }

    @Test
    fun `credential warnings escalate through the thresholds`() {
        val expectations = mapOf(
            -1L to appContext.getString(AutoR.string.session_credential_expired),
            5L to appContext.resources.getQuantityString(AutoR.plurals.session_credential_critical, 5, 5L),
            20L to appContext.resources.getQuantityString(AutoR.plurals.session_credential_urgent, 20, 20L),
            60L to appContext.resources.getQuantityString(AutoR.plurals.session_credential_warning, 60, 60L),
            200L to appContext.resources.getQuantityString(AutoR.plurals.session_credential_days, 200, 200L),
        )
        for ((days, expected) in expectations) {
            rule.setContent { SessionCard(session = bareSnapshot().copy(credentialDaysLeft = days)) }
            assertSingleText(expected)
        }
    }

    @Test
    fun `audio sinks describe streaming idle and missing states`() {
        rule.setContent { SessionCard(session = liveSnapshot()) }
        val streaming = appContext.getString(AutoR.string.session_audio_sink_streaming, "Full")
        assertSingleText(streaming)
        assertSingleText(appContext.getString(AutoR.string.session_audio_sink_idle))
        val mic = appContext.getString(AutoR.string.session_audio_mic_value, 3L, 2L)
        assertSingleText(mic)
    }


    @Test
    fun `native focus and unsupported sinks render their rows`() {
        val snapshot = bareSnapshot().copy(
            videoFocus = VideoFocus.NATIVE,
            audioFocus = AudioFocusState.AUDIO_FOCUS_STATE_LOSS,
            sinkStatus = mapOf(
                AudioSinkRole.MEDIA to AudioSinkStatus(AudioSinkRole.MEDIA, AudioSinkState.UNSUPPORTED, AudioGain.MUTED),
            ),
        )
        rule.setContent { SessionCard(session = snapshot) }
        assertSingleText(appContext.getString(AutoR.string.session_focus_native))
        assertSingleText(appContext.getString(AutoR.string.session_audio_sink_unsupported))
        assertSingleText(appContext.getString(AutoR.string.session_audio_sink_none))
        assertSingleText(appContext.getString(AutoR.string.session_surface_invalid))
        assertSingleText(appContext.getString(AutoR.string.session_input_allowed_no))
    }

    /**
     * Exactly one node carries [text]. The card overflows the test viewport,
     * so below-fold rows exist but are not "displayed" — exact-text counting
     * verifies what each row computed without depending on layout height.
     */
    private fun assertSingleText(text: String) {
        rule.onAllNodes(hasText(text)).assertCountEquals(1)
    }
}
