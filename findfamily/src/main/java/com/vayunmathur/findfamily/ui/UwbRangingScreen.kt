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
import com.vayunmathur.findfamily.platform.RangingSample
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.findfamily.platform.UwbSessionManager
import com.vayunmathur.library.ui.IconNavigationArrow
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

