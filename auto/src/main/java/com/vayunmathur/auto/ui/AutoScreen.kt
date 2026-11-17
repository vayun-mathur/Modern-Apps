package com.vayunmathur.auto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.auto.R
import com.vayunmathur.auto.data.PinnedAppsPrefs
import com.vayunmathur.auto.platform.AudioSinkStatus
import com.vayunmathur.auto.platform.AutoConnectionState
import com.vayunmathur.auto.platform.AutoViewModel
import com.vayunmathur.auto.platform.NowPlayingInfo
import com.vayunmathur.auto.platform.VideoInfo
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.VideoFocus
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior

/** Whether a car is attached, and what is running on it. */
@Composable
fun AutoScreen(viewModel: AutoViewModel, onPairing: () -> Unit, onPinnedApps: () -> Unit) {
    val state by viewModel.connection.collectAsStateWithLifecycle()
    val session = SessionSnapshot(
        video = viewModel.video.collectAsStateWithLifecycle().value,
        focusMode = viewModel.focusMode.collectAsStateWithLifecycle().value,
        videoFocus = viewModel.videoFocus.collectAsStateWithLifecycle().value,
        audioFocus = viewModel.audioFocus.collectAsStateWithLifecycle().value,
        inputAllowed = viewModel.inputAllowed.collectAsStateWithLifecycle().value,
        framesSent = viewModel.framesSent.collectAsStateWithLifecycle().value,
        acksSeen = viewModel.acksSeen.collectAsStateWithLifecycle().value,
        ackMismatches = viewModel.ackMismatches.collectAsStateWithLifecycle().value,
        encodedFps = viewModel.encodedFps.collectAsStateWithLifecycle().value,
        ackFps = viewModel.ackFps.collectAsStateWithLifecycle().value,
        lastAckAt = viewModel.lastAckAt.collectAsStateWithLifecycle().value,
        lastAckSeq = viewModel.lastAckSeq.collectAsStateWithLifecycle().value,
        avgEncodeLatencyUs = viewModel.avgEncodeLatencyUs.collectAsStateWithLifecycle().value,
        encoderDrains = viewModel.encoderDrains.collectAsStateWithLifecycle().value,
        surfaceValid = viewModel.surfaceValid.collectAsStateWithLifecycle().value,
        credentialDaysLeft = viewModel.credentialDaysLeft.collectAsStateWithLifecycle().value,
        sessionStartedAt = viewModel.sessionStartedAt.collectAsStateWithLifecycle().value,
        nowPlaying = viewModel.nowPlaying.collectAsStateWithLifecycle().value,
        threadsPosted = viewModel.threadsPosted.collectAsStateWithLifecycle().value,
        messagesPosted = viewModel.messagesPosted.collectAsStateWithLifecycle().value,
        repliesReceived = viewModel.repliesReceived.collectAsStateWithLifecycle().value,
        sinkStatus = viewModel.sinkStatus.collectAsStateWithLifecycle().value,
        audioBytesSent = viewModel.audioBytesSent.collectAsStateWithLifecycle().value,
        micTurns = viewModel.micTurns.collectAsStateWithLifecycle().value,
        micAcks = viewModel.micAcks.collectAsStateWithLifecycle().value,
        ttsSpoken = viewModel.ttsSpoken.collectAsStateWithLifecycle().value,
        touchesInjected = viewModel.touchesInjected.collectAsStateWithLifecycle().value,
        keysInjected = viewModel.keysInjected.collectAsStateWithLifecycle().value,
        scrollsInjected = viewModel.scrollsInjected.collectAsStateWithLifecycle().value,
        inputDropped = viewModel.inputDropped.collectAsStateWithLifecycle().value,
        musicBytesCaptured = viewModel.musicBytesCaptured.collectAsStateWithLifecycle().value,
    )
    AutoScreen(state = state, session = session, onPairing = onPairing, onPinnedApps = onPinnedApps)
}

/**
 * Stateless status screen: connection row, pin-picker entry, session stats and
 * consent cards. The ViewModel overload collects the flows and delegates here;
 * metadata previews render this directly with literals.
 */
@Composable
fun AutoScreen(
    state: AutoConnectionState,
    session: SessionSnapshot,
    onPairing: () -> Unit,
    onPinnedApps: () -> Unit,
) {
    val scrollBehavior = appBarScrollBehavior()
    AppScaffold(
        title = stringResource(R.string.app_name),
        scrollBehavior = scrollBehavior,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.connection)) },
                    supportingContent = { Text(state.describe()) },
                    trailingContent = {
                        TextButton(onClick = onPairing) {
                            Text(stringResource(R.string.pairing_open))
                        }
                    },
                )
            }
            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pinned_apps_title)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.pinned_apps_body, PinnedAppsPrefs.MAX_PINNED, PinnedAppsPrefs.MAX_PINNED)) },
                    trailingContent = {
                        TextButton(onClick = onPinnedApps) {
                            Text(stringResource(R.string.pinned_apps_title))
                        }
                    },
                )
            }
            SessionCard(session = session, modifier = Modifier.padding(top = 16.dp))
            MusicCaptureCard(modifier = Modifier.padding(top = 16.dp))
            MessagingConsentCard(modifier = Modifier.padding(top = 16.dp))
            MicPermissionCard(modifier = Modifier.padding(top = 16.dp))
            Text(
                text = stringResource(R.string.connect_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

/** Whatever the session card needs that is not the connection state itself. */
data class SessionSnapshot(
    val video: VideoInfo?,
    /** Raw 0x8008 mode string; the arbitrated rows below are authoritative. */
    val focusMode: String?,
    /** Arbitrated video focus, mirrored from the session. */
    val videoFocus: VideoFocus,
    /** Last audio focus state the head unit reported. */
    val audioFocus: AudioFocusState,
    /** Whether head-unit input may currently be injected. */
    val inputAllowed: Boolean,
    val framesSent: Long,
    val acksSeen: Long,
    val ackMismatches: Long,
    /** Encode-side frames per second; sent, never decoded or shown. */
    val encodedFps: Double,
    /** Head-unit acks per second; receipt, not visibility. */
    val ackFps: Double,
    /** Wall-clock millis of the last ack; null before the first. Drives the live age. */
    val lastAckAt: Long?,
    /** Highest 0x8004 ack counter seen; null until an ack carries the field. */
    val lastAckSeq: Long?,
    /** Rolling mean encode-to-send latency in microseconds, or null with no frames. */
    val avgEncodeLatencyUs: Long?,
    /** Encoder drains so far. */
    val encoderDrains: Long,
    /** Whether the virtual display + encoder input surface pair is up. */
    val surfaceValid: Boolean,
    /** Whole days until the shipped GAL leaf expires; null until the service seeds it. */
    val credentialDaysLeft: Long?,
    val sessionStartedAt: Long?,
    /** Latest now-playing snapshot; null until the media session reports. */
    val nowPlaying: NowPlayingInfo?,
    /** ch14 thread snapshots posted this session. */
    val threadsPosted: Long,
    /** ch14 message bodies posted this session. */
    val messagesPosted: Long,
    /** Head-unit replies (typed or voice) received this session. */
    val repliesReceived: Long,
    /** Per-role sink status (ch4 SYS, ch5 MEDIA); empty until a channel opens. */
    val sinkStatus: Map<AudioSinkRole, AudioSinkStatus>,
    /** PCM bytes framed out on ch4/5 this session. */
    val audioBytesSent: Long,
    /** ch6 mic turns that yielded retained PCM this session. */
    val micTurns: Long,
    /** ch6 mic chunks acked upstream this session. */
    val micAcks: Long,
    /** TTS utterances that reached the car this session. */
    val ttsSpoken: Long,
    /** ch8 touch frames injected this session. */
    val touchesInjected: Long,
    /** ch8 key presses/releases injected this session. */
    val keysInjected: Long,
    /** ch8 scroll ticks injected this session. */
    val scrollsInjected: Long,
    /** ch8 reports dropped for lack of input focus this session. */
    val inputDropped: Long,
    /** Music PCM bytes captured into the ch5 sink this session. */
    val musicBytesCaptured: Long,
)

@Composable
private fun AutoConnectionState.describe(): String = when (this) {
    AutoConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
    AutoConnectionState.Connecting -> stringResource(R.string.state_connecting)
    is AutoConnectionState.Projecting -> stringResource(R.string.state_projecting, carName)
    AutoConnectionState.Rejected -> stringResource(R.string.state_rejected)
}
