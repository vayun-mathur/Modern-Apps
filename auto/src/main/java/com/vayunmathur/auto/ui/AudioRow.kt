package com.vayunmathur.auto.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.AudioSinkState
import com.vayunmathur.auto.platform.AudioSinkStatus
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text

/**
 * What ch4/5/6 did this session: per-sink bring-up state and arbitrated
 * gain, PCM bytes framed out, mic turns and acks, TTS utterances spoken.
 * Counters reset per socket, like the frame/ack counts above.
 */
@Composable
fun AudioRow(
    sinkStatus: Map<AudioSinkRole, AudioSinkStatus>,
    bytesSent: Long,
    musicCaptured: Long,
    micTurns: Long,
    micAcks: Long,
    ttsSpoken: Long,
) {
    val sys = sinkStatus[AudioSinkRole.SYSTEM]
    val media = sinkStatus[AudioSinkRole.MEDIA]
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_audio_sys)) },
        supportingContent = { Text(sys.describe()) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_audio_media)) },
        supportingContent = { Text(media.describe()) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_audio_bytes)) },
        supportingContent = { Text(stringResource(R.string.session_stat_value, bytesSent)) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_audio_music_captured)) },
        supportingContent = { Text(stringResource(R.string.session_stat_value, musicCaptured)) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_audio_mic)) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.session_audio_mic_value, micTurns.toInt(), micTurns, micAcks))
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_audio_tts)) },
        supportingContent = { Text(stringResource(R.string.session_stat_value, ttsSpoken)) },
    )
}

@Composable
private fun AudioSinkStatus?.describe(): String = when {
    this == null -> stringResource(R.string.session_audio_sink_none)
    state == AudioSinkState.UNSUPPORTED -> stringResource(R.string.session_audio_sink_unsupported)
    state == AudioSinkState.STARTED -> stringResource(
        R.string.session_audio_sink_streaming,
        gain.name.lowercase().replaceFirstChar { it.uppercase() },
    )
    else -> stringResource(R.string.session_audio_sink_idle)
}
