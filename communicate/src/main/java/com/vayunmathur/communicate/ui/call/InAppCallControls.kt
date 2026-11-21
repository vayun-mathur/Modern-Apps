package com.vayunmathur.communicate.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.call.InAppCallState
import com.vayunmathur.communicate.ui.initialsFor
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconCallEnd
import com.vayunmathur.library.ui.IconCameraOff
import com.vayunmathur.library.ui.IconDialpad
import com.vayunmathur.library.ui.IconFlipCamera
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconMicOff
import com.vayunmathur.library.ui.IconScreenShare
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip

/** Peer identity and call status. The monogram is dropped when video occupies the screen. */
@Composable
internal fun CallHeader(state: InAppCallState, showMonogram: Boolean) {
    if (showMonogram) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initialsFor(state.peerName),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(Spacing.xl))
    }
    Text(
        state.peerName,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(Spacing.sm))
    Text(
        callStatusText(state),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.phase == InAppCallPhase.Active && state.connectedAtMs > 0L) {
        Spacer(Modifier.height(Spacing.xs))
        CallDuration(state.connectedAtMs)
    }
}

/** Ticks once a second, so only this subtree recomposes as the call runs. */
@Composable
internal fun CallDuration(connectedAtMs: Long) {
    var elapsed by remember(connectedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(connectedAtMs) {
        while (true) {
            elapsed = (System.currentTimeMillis() - connectedAtMs).coerceAtLeast(0L) / 1000
            delay(1000)
        }
    }
    Text(
        "%d:%02d".format(elapsed / 60, elapsed % 60),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Decline and answer, weighted equally but coloured oppositely so they cannot be confused. */
@Composable
internal fun IncomingControls(onAnswer: () -> Unit = { InAppCallRegistry.answer() }) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CallAction(
            label = stringResource(R.string.call_decline),
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
            size = PRIMARY_ACTION,
            onClick = { InAppCallRegistry.reject() },
        ) { IconCallEnd(tint = it) }
        CallAction(
            label = stringResource(R.string.call_answer),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            size = PRIMARY_ACTION,
            onClick = onAnswer,
        ) { IconCall(tint = it) }
    }
}

/**
 * In-call controls, drawn from [CallCapabilities]: only what the line supports appears. Hang up is the
 * largest and the only destructive colour, so it reads as the primary action.
 */
@Composable
internal fun OngoingControls(
    state: InAppCallState,
    keypadShown: Boolean,
    onToggleKeypad: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onToggleVideo: () -> Unit = { InAppCallRegistry.toggleVideo() },
) {
    val caps = state.capabilities
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (caps.mute) {
                CallToggle(
                    label = stringResource(R.string.call_mute),
                    active = state.muted,
                    onClick = { InAppCallRegistry.toggleMuted() },
                ) { tint -> if (state.muted) IconMicOff(tint = tint) else IconMic(tint = tint) }
            }
            // Speaker and camera-flip share one slot: sending video forces hands-free, so the speaker
            // control is meaningless then and flip only applies then. Swapping them in place keeps every
            // other control — the video toggle especially — from shifting as video comes on and off.
            if (caps.speaker || caps.video) {
                if (state.localVideoEnabled && !state.screenSharing) {
                    CallToggle(
                        label = stringResource(R.string.call_flip_camera),
                        active = false,
                        onClick = { InAppCallRegistry.flipCamera() },
                    ) { tint -> IconFlipCamera(tint = tint) }
                } else if (caps.speaker) {
                    CallToggle(
                        label = stringResource(R.string.call_speaker),
                        active = state.speaker,
                        onClick = { InAppCallRegistry.toggleSpeaker() },
                    ) { tint -> IconVolumeUp(tint = tint) }
                }
            }
            if (caps.video) {
                CallToggle(
                    label = stringResource(R.string.call_video),
                    active = state.localVideoEnabled,
                    onClick = onToggleVideo,
                ) { tint ->
                    if (state.localVideoEnabled) IconVideoCamera(tint = tint) else IconCameraOff(tint = tint)
                }
            }
            if (caps.screenShare) {
                CallToggle(
                    label = stringResource(R.string.call_screen_share),
                    active = state.screenSharing,
                    onClick = onToggleScreenShare,
                ) { tint -> IconScreenShare(tint = tint) }
            }
            if (caps.hold) {
                CallToggle(
                    label = stringResource(R.string.call_hold),
                    active = state.held,
                    onClick = { InAppCallRegistry.toggleHeld() },
                ) { tint -> IconPause(tint = tint) }
            }
            if (caps.dtmf) {
                CallToggle(
                    label = stringResource(R.string.call_keypad),
                    active = keypadShown,
                    onClick = onToggleKeypad,
                ) { tint -> IconDialpad(tint = tint) }
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        CallAction(
            label = stringResource(R.string.call_end),
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
            size = PRIMARY_ACTION,
            onClick = { InAppCallRegistry.hangup() },
        ) { IconCallEnd(tint = it) }
    }
}

/** A large, colour-carrying circular action. The library's FilledIconButton takes no colours. */
@Composable
internal fun CallAction(
    label: String,
    container: Color,
    content: Color,
    size: Int,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            // Both dimensions, so the circle cannot be squashed by the row's constraints.
            modifier = Modifier.size(width = size.dp, height = size.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon(content) }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** A secondary toggle. Filled when on, tonal when off, so state is visible without reading the icon. */
@Composable
internal fun CallToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val container = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.size(width = SECONDARY_ACTION.dp, height = SECONDARY_ACTION.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon(content) }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tone dialling, for lines that reach the PSTN. */
@Composable
internal fun DtmfKeypad(onDigit: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                row.forEach { digit ->
                    Surface(
                        onClick = { onDigit(digit) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(digit, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun callStatusText(state: InAppCallState): String {
    val lineName = when (state.line) {
        CommunicateLine.Signal -> stringResource(R.string.account_signal)
        CommunicateLine.WhatsApp -> stringResource(R.string.account_whatsapp)
        CommunicateLine.GoogleVoice -> stringResource(R.string.account_google_voice)
        CommunicateLine.Sim -> stringResource(R.string.account_sim)
        CommunicateLine.Rcs -> stringResource(R.string.account_rcs)
        else -> ""
    }
    return when (state.phase) {
        InAppCallPhase.Outgoing -> stringResource(R.string.call_state_dialing)
        InAppCallPhase.Incoming -> if (lineName.isBlank()) {
            stringResource(R.string.call_state_incoming_generic)
        } else {
            "${stringResource(R.string.call_state_incoming_generic)} - $lineName"
        }
        InAppCallPhase.Connecting -> stringResource(R.string.call_state_connecting)
        // Once connected the duration carries the status, so name the line instead — unless held.
        InAppCallPhase.Active -> if (state.held) {
            stringResource(R.string.call_state_on_hold)
        } else {
            lineName
        }
        InAppCallPhase.Ended -> state.endReason?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.call_state_ended)
        InAppCallPhase.Idle -> ""
    }
}

internal const val PRIMARY_ACTION = 72
internal const val SECONDARY_ACTION = 56
