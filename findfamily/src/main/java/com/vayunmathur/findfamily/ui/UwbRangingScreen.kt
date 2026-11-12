package com.vayunmathur.findfamily.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberPermissionRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.uwb.RangingSample
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.UwbSessionManager
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

/**
 * Full-screen UWB Find Nearby view. Shows distance + a directional
 * arrow to the selected peer.
 *
 * The screen owns the UWB session lifecycle: it triggers [FindFamilyViewModel.startRanging]
 * on first composition and [FindFamilyViewModel.stopRanging] on dispose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UwbRangingScreen(
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    peerUserId: Long
) {
    val context = LocalContext.current

    val peer by ffViewModel.userByIdState(peerUserId)
    val session by ffViewModel.uwbSession.collectAsState()
    val activePeerId by ffViewModel.uwbPeerUserId.collectAsState()

    // Runtime android.permission.RANGING gate (required by the public
    // android.ranging API on Android 15+). UWB_RANGING is the legacy perm
    // for androidx.core.uwb's GMS path — we no longer use that path.
    var hasRangingPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                "android.permission.RANGING"
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val requestRangingPermission = rememberPermissionRequest(
        "android.permission.RANGING"
    ) { granted -> hasRangingPermission = granted }

    LaunchedEffect(hasRangingPermission) {
        if (!hasRangingPermission) {
            requestRangingPermission()
            return@LaunchedEffect
        }
        // Three cases when the screen opens:
        //  (a) session is Idle           → user wants to find peer; start as initiator.
        //  (b) session is active for THIS peer → service already auto-accepted; just observe.
        //  (c) session is active for ANOTHER peer → busy; show a Failed state (one session at a time).
        when {
            session is UwbSessionManager.UwbSessionState.Idle -> {
                ffViewModel.startRanging(peerUserId)
            }
            activePeerId != null && activePeerId != peerUserId -> {
                // Showing the screen for a different peer than the active session.
                // Don't stomp on the ongoing session — just display its current state
                // (which won't match this peer) so the user understands.
            }
            // else: session is for THIS peer — observe in place.
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Only end the session if it's the one this screen was watching.
            if (activePeerId == peerUserId) {
                ffViewModel.stopRanging()
            }
        }
    }

    UwbRangingContent(
        peerName = peer?.name ?: stringResource(UiR.string.unknown),
        session = session,
        onBack = { backStack.pop() }
    )
}

/**
 * The rendered half of [UwbRangingScreen] — no ViewModel, no session lifecycle — so the
 * store-listing preview can draw it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UwbRangingContent(
    peerName: String,
    session: UwbSessionManager.UwbSessionState,
    onBack: () -> Unit
) {
    AppScaffold(
        title = peerName,
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
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
private fun StatusBlock(main: String, sub: String? = null, progress: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        if (progress) {
            CircularProgressIndicator()
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
