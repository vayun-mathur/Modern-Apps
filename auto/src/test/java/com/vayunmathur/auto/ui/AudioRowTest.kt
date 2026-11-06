package com.vayunmathur.auto.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.R as AutoR
import com.vayunmathur.auto.platform.AudioSinkState
import com.vayunmathur.auto.platform.AudioSinkStatus
import com.vayunmathur.auto.protocol.AudioGain
import com.vayunmathur.auto.protocol.AudioSinkRole
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

/**
 * Auto module: [AudioRow] — the ch4/5/6 status block. A pure function of its
 * arguments: sink bring-up descriptions plus the byte/mic/TTS counters.
 */
@RunWith(RobolectricTestRunner::class)
class AudioRowTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun streaming(role: AudioSinkRole) =
        AudioSinkStatus(role, AudioSinkState.STARTED, AudioGain.FULL)

    @Test
    fun `empty sinks render the none placeholders and zero counters`() {
        rule.setContent {
            AudioRow(
                sinkStatus = emptyMap(),
                bytesSent = 0L,
                musicCaptured = 0L,
                micTurns = 0L,
                micAcks = 0L,
                ttsSpoken = 0L,
            )
        }
        // Two "none" rows: SYS and MEDIA.
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.session_audio_sink_none)))
            .assertCountEquals(2)
        val zero = appContext.getString(AutoR.string.session_stat_value, 0L)
        rule.onAllNodes(hasText(zero)).assertCountEquals(3)
        val mic = appContext.getString(AutoR.string.session_audio_mic_value, 0L, 0L)
        rule.onAllNodes(hasText(mic)).assertCountEquals(1)
    }

    @Test
    fun `streaming sinks describe their gain and counters render their values`() {
        rule.setContent {
            AudioRow(
                sinkStatus = mapOf(
                    AudioSinkRole.SYSTEM to streaming(AudioSinkRole.SYSTEM),
                    AudioSinkRole.MEDIA to AudioSinkStatus(
                        AudioSinkRole.MEDIA,
                        AudioSinkState.IDLE,
                        AudioGain.FULL,
                    ),
                ),
                bytesSent = 4096L,
                musicCaptured = 2048L,
                micTurns = 3L,
                micAcks = 2L,
                ttsSpoken = 1L,
            )
        }
        val streaming = appContext.getString(AutoR.string.session_audio_sink_streaming, "Full")
        rule.onAllNodes(hasText(streaming)).assertCountEquals(1)
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.session_audio_sink_idle)))
            .assertCountEquals(1)
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.session_stat_value, 4096L)))
            .assertCountEquals(1)
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.session_stat_value, 2048L)))
            .assertCountEquals(1)
        val mic = appContext.getString(AutoR.string.session_audio_mic_value, 3L, 2L)
        rule.onAllNodes(hasText(mic)).assertCountEquals(1)
    }

    @Test
    fun `unsupported sinks render the unsupported row`() {
        rule.setContent {
            AudioRow(
                sinkStatus = mapOf(
                    AudioSinkRole.SYSTEM to AudioSinkStatus(
                        AudioSinkRole.SYSTEM,
                        AudioSinkState.UNSUPPORTED,
                        AudioGain.MUTED,
                    ),
                ),
                bytesSent = 0L,
                musicCaptured = 0L,
                micTurns = 0L,
                micAcks = 0L,
                ttsSpoken = 0L,
            )
        }
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.session_audio_sink_unsupported)))
            .assertCountEquals(1)
        // MEDIA still has no entry.
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.session_audio_sink_none)))
            .assertCountEquals(1)
    }
}
