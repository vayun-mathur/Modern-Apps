package com.vayunmathur.cast.tv.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.cast.tv.R
import com.vayunmathur.cast.tv.Route
import com.vayunmathur.cast.tv.platform.ReceiverFailure
import com.vayunmathur.cast.tv.platform.ReceiverUiState
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.ui.IconCastConnected
import com.vayunmathur.library.ui.IconScreenShare
import com.vayunmathur.library.ui.IconWarning

/**
 * Every full-screen state of the receiver that is not the picture itself.
 *
 * Renders one [Route] destination: the navigation host (Navigation.kt)
 * chooses the destination from the controller's phase, and this renders
 * only what that destination names — destination arguments first, and the
 * beside-the-phase state (device/network labels) from [state]. The picture
 * gets `MirrorActivity`; the now-playing artwork screen lives there too,
 * beside the surface it accompanies. Nothing here paints a base background
 * - the overlay case above a decoder surface needs transparency, so
 * screens that want colour say so themselves.
 */
@Composable
fun ReceiverContent(route: Route, state: ReceiverUiState, modifier: Modifier = Modifier) {
    if (state.localNetworkBlocked) {
        NetworkBlockedScreen(modifier)
        return
    }
    when (route) {
        Route.Starting -> StartingScreen(modifier)

        Route.Ready -> ReadyScreen(
            deviceName = state.deviceName.ifBlank { stringResource(R.string.tv_name_unknown) },
            networkLabel = state.networkLabel,
            modifier = modifier,
        )

        is Route.Pairing -> PairingScreen(
            senderName = route.senderName,
            code = route.code,
            attemptsLeft = route.attemptsLeft,
            codeChanged = route.codeChanged,
            modifier = modifier,
        )

        // Paired; the stream is being set up. `MainActivity` moves on to `MirrorActivity` the
        // moment frames arrive, so this is a held breath rather than a place anyone stays.
        is Route.Connected -> ConnectedScreen(senderName = route.senderName, modifier = modifier)

        is Route.Failed -> FailedScreen(reason = route.reason, modifier = modifier)
    }
}

@Composable
private fun StartingScreen(modifier: Modifier = Modifier) {
    TvLoading(
        modifier = modifier.fillMaxSize(),
        label = stringResource(R.string.tv_starting),
    )
}

@Composable
private fun ConnectedScreen(senderName: String, modifier: Modifier = Modifier, mirroring: Boolean = false) {
    val focusRequester = remember { FocusRequester() }
    TvInitialFocus(focusRequester)
    TvEmptyState(
        title = stringResource(
            if (mirroring) R.string.tv_mirroring else R.string.tv_paired,
            senderName,
        ),
        icon = { if (mirroring) IconScreenShare() else IconCastConnected() },
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
    )
}

/**
 * The receiver could not advertise itself: Android refused the mDNS registration, so no phone
 * will ever list this TV. Distinct from "nothing has connected yet" - the fix is a permission,
 * not opening the app on the phone.
 */
@Composable
private fun NetworkBlockedScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    TvEmptyState(
        title = stringResource(R.string.tv_local_network_blocked),
        body = stringResource(R.string.tv_local_network_blocked_hint),
        icon = { IconWarning() },
        isError = true,
        action = {
            TvButton(
                text = stringResource(R.string.tv_local_network_open_settings),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
                        },
                    )
                },
                primary = true,
                modifier = Modifier.focusRequester(focusRequester),
            )
            TvInitialFocus(focusRequester)
        },
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
    )
}

/**
 * Something the user has to know about, already a sentence rather than a code. Each failure has
 * a different answer, which is why the contract distinguishes them; the screen keeps the shared
 * shape and varies the copy.
 */
@Composable
private fun FailedScreen(reason: ReceiverFailure, modifier: Modifier = Modifier) {
    val message = stringResource(
        when (reason) {
            ReceiverFailure.NoDecoder -> R.string.tv_no_decoder
            ReceiverFailure.NoAudioDecoder -> R.string.tv_no_audio_decoder
            ReceiverFailure.MissingCodecConfig -> R.string.tv_missing_codec_config
            ReceiverFailure.Handshake -> R.string.tv_stream_failed
            ReceiverFailure.StreamEnded -> R.string.tv_stream_failed
        },
    )
    // No retry button to offer: every one of these is fixed by the phone trying again (a new
    // session) or by nothing the TV can do (no decoder). A button that re-runs nothing would
    // be the cruellest control on the screen.
    TvEmptyState(
        title = message,
        icon = { IconWarning() },
        isError = true,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
    )
}
