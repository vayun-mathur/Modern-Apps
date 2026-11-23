package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.platform.RangingSample
import com.vayunmathur.findfamily.platform.UwbSessionManager
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.IconNavigationArrow


/**
 * The rendered half of [UwbRangingScreen] — no ViewModel, no session lifecycle — so the
 * store-listing preview can draw it.
 *
 * When [showStart] is true the purpose text and Start button replace the session status:
 * nothing (permission request included) fires until the user taps. Defaults keep the
 * preview/test call sites showing session state directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UwbRangingContent(
    peerName: String,
    session: UwbSessionManager.UwbSessionState,
    onBack: () -> Unit,
    showStart: Boolean = false,
    onStart: () -> Unit = {},
) {
    AppScaffold(
        title = peerName,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            if (showStart) {
                StartBlock(onStart = onStart)
                return@Column
            }
            when (val s = session) {
                UwbSessionManager.UwbSessionState.Idle,
                UwbSessionManager.UwbSessionState.Starting -> StatusBlock(
                    main = stringResource(R.string.uwb_status_starting),
                    progress = true
                )
                UwbSessionManager.UwbSessionState.WaitingForPeer -> StatusBlock(
                    main = stringResource(R.string.uwb_status_waiting),
                    progress = true
                )
                UwbSessionManager.UwbSessionState.PeerDisconnected -> StatusBlock(
                    main = stringResource(R.string.uwb_status_peer_disconnected),
                    progress = false
                )
                is UwbSessionManager.UwbSessionState.Unsupported -> StatusBlock(
                    main = stringResource(R.string.uwb_status_unsupported),
                    sub = s.reason,
                    progress = false
                )
                UwbSessionManager.UwbSessionState.PeerUnsupported -> StatusBlock(
                    main = stringResource(R.string.uwb_status_peer_unsupported),
                    progress = false
                )
                is UwbSessionManager.UwbSessionState.Failed -> StatusBlock(
                    main = stringResource(R.string.uwb_status_failed),
                    sub = s.reason,
                    progress = false
                )
                is UwbSessionManager.UwbSessionState.Ranging -> RangingBody(s.sample)
            }
        }
    }
}

@Composable
private fun StartBlock(onStart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.uwb_permission_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.uwb_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStart) {
            Text(stringResource(R.string.uwb_start_action))
        }
    }
}

@Composable
private fun StatusBlock(main: String, sub: String? = null, progress: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        if (progress) {
            Spacer(Modifier.height(16.dp))
        }
        Text(
            main,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        if (sub != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RangingBody(sample: RangingSample) {
    val distance = sample.distanceMeters
    val azimuth = sample.azimuthDeg

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxSize()
    ) {
        // Distance readout
        Text(
            text = distance?.let { "%.1f m".format(it) } ?: "—",
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Directional arrow — raw azimuth from the radio (low-confidence
        // samples have already been filtered out in UwbController).
        Box(
            modifier = Modifier.size(220.dp),
            contentAlignment = Alignment.Center
        ) {
            IconNavigationArrow(
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(180.dp)
                    .alpha(if (azimuth != null) 1f else 0.25f)
                    .graphicsLayer { rotationZ = azimuth ?: 0f }
            )
        }

        // Debug overlay
        val distText = stringResource(R.string.dist)
        val azText = stringResource(R.string.az)
        val elText = stringResource(R.string.el)
        Text(
            text = buildString {
                append(distText).append(distance?.let { "%.2f m".format(it) } ?: "—")
                append(azText).append(azimuth?.let { "%+.1f°".format(it) } ?: "—")
                append(elText).append(sample.elevationDeg?.let { "%+.1f°".format(it) } ?: "—")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (azimuth == null) {
            Text(
                text = stringResource(R.string.point_the_back_of_your_phone_toward_your),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
