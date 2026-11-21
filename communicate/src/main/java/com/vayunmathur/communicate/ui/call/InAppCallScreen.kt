package com.vayunmathur.communicate.ui.call

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.call.requiresInAppCallPermissions
import com.vayunmathur.communicate.ui.rememberInAppCallPermissionStarter
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.scrim
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.delay

/**
 * The call screen for every in-app line — Google Voice, WhatsApp and Signal.
 *
 * One screen rather than one per line, driven by [CallCapabilities]: Google Voice offers a keypad and no
 * camera, the other two the reverse, and a control the line cannot honour is not drawn at all. That keeps
 * the layout honest — nothing on screen is inert.
 *
 * Hierarchy comes from size and colour rather than from a row of identical buttons: the peer is a large
 * monogram, the primary action (answer / hang up) is a bigger coloured circle, and secondary toggles are
 * smaller and tonal.
 */
@Composable
fun InAppCallScreen(onClose: () -> Unit) {
    val state by InAppCallRegistry.state.collectAsState()
    var showKeypad by remember { mutableStateOf(false) }

    // MediaProjection consent must be granted per share; the result Intent is the capture token.
    val screenShareConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val permission = result.data
        if (result.resultCode == Activity.RESULT_OK && permission != null) {
            InAppCallRegistry.setScreenShare(true, permission)
        }
    }
    val context = LocalContext.current
    val callAudioRationale = stringResource(R.string.permission_call_audio_message)
    val callVideoRationale = stringResource(R.string.permission_call_video_message)
    val callAudioDenied = stringResource(R.string.call_not_started_no_audio_permission)
    val callVideoDenied = stringResource(R.string.call_not_started_no_video_permission)
    // Answering (or turning the camera on) is a call start for the in-app media lines: do not
    // capture mic/camera until permission is granted. Denial leaves the call ringing / video off
    // and explains why via the messenger. SIM (Telecom) calls need no such permission.
    val startCallWithPermission = rememberInAppCallPermissionStarter(
        onRationale = { video -> AppMessages.show(if (video) callVideoRationale else callAudioRationale) },
        onDenied = { video -> AppMessages.show(if (video) callVideoDenied else callAudioDenied) },
    )
    fun gatedAnswer() {
        val answer = { InAppCallRegistry.answer() }
        if (state.line?.requiresInAppCallPermissions() == true) {
            startCallWithPermission(state.isVideo, answer)
        } else {
            answer()
        }
    }
    fun gatedToggleVideo() {
        // Turning video off never needs permission; turning it on is a video-call start.
        if (state.localVideoEnabled || state.line?.requiresInAppCallPermissions() != true) {
            InAppCallRegistry.toggleVideo()
        } else {
            startCallWithPermission(true) { InAppCallRegistry.toggleVideo() }
        }
    }

    // A terminal state is held briefly so the outcome is readable, then dismissed.
    LaunchedEffect(state.phase) {
        when (state.phase) {
            InAppCallPhase.Ended -> {
                delay(1500)
                InAppCallRegistry.clearEnded()
                onClose()
            }
            InAppCallPhase.Idle -> onClose()
            else -> Unit
        }
    }

    val videoActive = state.remoteVideoEnabled || state.localVideoEnabled

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (videoActive) {
                CallVideo(showRemote = state.remoteVideoEnabled, showLocal = state.localVideoEnabled)
                // Keeps the name and controls legible over arbitrary video.
                Box(modifier = Modifier.fillMaxSize().scrim { 0.35f })
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(Spacing.xxl))
                CallHeader(state = state, showMonogram = !videoActive)

                Spacer(Modifier.weight(1f))

                if (showKeypad && state.capabilities.dtmf) {
                    DtmfKeypad(onDigit = { InAppCallRegistry.sendDtmf(it) })
                    Spacer(Modifier.height(Spacing.lg))
                }

                when (state.phase) {
                    InAppCallPhase.Incoming -> IncomingControls(onAnswer = ::gatedAnswer)
                    InAppCallPhase.Ended -> Unit
                    else -> OngoingControls(
                        state = state,
                        keypadShown = showKeypad,
                        onToggleKeypad = { showKeypad = !showKeypad },
                        onToggleVideo = ::gatedToggleVideo,
                        onToggleScreenShare = {
                            if (state.screenSharing) {
                                InAppCallRegistry.setScreenShare(false, null)
                            } else {
                                val manager = context.getSystemService(MediaProjectionManager::class.java)
                                manager?.createScreenCaptureIntent()?.let(screenShareConsent::launch)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}
