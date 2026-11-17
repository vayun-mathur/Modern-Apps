package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.R
import com.vayunmathur.keyboard.platform.ime.VoiceState
import com.vayunmathur.keyboard.platform.VoiceFailure
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconMicOff
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconStop
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * The dictation strip, which takes over the slot above the keys for as long as voice input
 * has something to say.
 *
 * An IME cannot open a dialog — that would take focus from the very field being dictated
 * into — so this row is the whole of the feedback: it shows that the microphone is live,
 * echoes the words as the recognizer hears them, and carries the button that ends the
 * session. A failure stays here too, next to the affordance that can fix it, rather than
 * going out as a message no scaffold in an IME would be collecting.
 */
@Composable
fun VoiceStrip(
    height: Dp,
    state: VoiceState,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val failed = state as? VoiceState.Failed
        val tint = if (failed != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (failed != null) {
                IconMicOff(modifier = Modifier.size(18.dp), tint = tint)
            } else {
                IconMic(modifier = Modifier.size(18.dp), tint = tint)
            }
        }
        Text(
            text = label(state),
            color = if (failed != null) tint else MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (failed?.failure == VoiceFailure.PERMISSION_BLOCKED) {
            StripButton(onClick = onOpenSettings) {
                IconSettings(
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StripButton(onClick = if (state is VoiceState.Listening) onStop else onDismiss) {
            val buttonTint = MaterialTheme.colorScheme.onSurfaceVariant
            if (state is VoiceState.Listening) {
                IconStop(modifier = Modifier.size(20.dp), tint = buttonTint)
            } else {
                IconClose(modifier = Modifier.size(18.dp), tint = buttonTint)
            }
        }
    }
}

@Composable
private fun StripButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val tick = rememberKeyHapticTick()
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                tick()
                onClick()
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The strip's one line of text. Partial results replace "Listening…" as soon as any arrive. */
@Composable
private fun label(state: VoiceState): String = when (state) {
    is VoiceState.Listening ->
        state.partial.ifBlank { stringResource(R.string.voice_listening) }
    VoiceState.Transcribing -> stringResource(R.string.voice_transcribing)
    is VoiceState.Failed -> stringResource(
        when (state.failure) {
            VoiceFailure.UNAVAILABLE -> R.string.voice_unavailable
            VoiceFailure.PERMISSION_DENIED -> R.string.voice_permission_denied
            VoiceFailure.PERMISSION_BLOCKED -> R.string.voice_permission_blocked
            VoiceFailure.NETWORK -> R.string.voice_network
            VoiceFailure.NO_SPEECH -> R.string.voice_no_speech
            VoiceFailure.BUSY -> R.string.voice_busy
            VoiceFailure.AUDIO -> R.string.voice_audio_error
            VoiceFailure.OTHER -> R.string.voice_failed
        },
    )
}
