package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.hub.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.MainRoute
import com.vayunmathur.games.hub.network.PlayOnTvSession
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.util.NavBackStack

/**
 * "Play on TV": this phone as a controller for a TV-hosted game.
 *
 * The session owns discovery + client + rumble and reduces them to one
 * [ControllerPadState]; this screen only binds
 * it to the reusable [ControllerPad] and tears the session down on leave.
 * The callbacks map one-to-one onto the session (`onConnect` → `connect`,
 * `onProveCode` → `proveCode`, …), exactly as the SDK's pad contract documents.
 *
 * mDNS browsing needs `ACCESS_LOCAL_NETWORK` on Android 16+, which is a
 * runtime grant — without it the NSD daemon refuses the browse and the pad
 * would spin on "looking" forever. Request it on entry; on grant, rescan so
 * a previously refused browse restarts.
 */
@Composable
fun PlayOnTvScreen(
    backStack: NavBackStack<MainRoute>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val session = remember { PlayOnTvSession(context) }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsStateWithLifecycle()
    val requestLocalNetwork = rememberPermissionRequest(
        android.Manifest.permission.ACCESS_LOCAL_NETWORK,
        onResult = { granted -> if (granted) session.rescan() },
    )
    LaunchedEffect(Unit) { requestLocalNetwork() }

    AppScaffold(
        title = stringResource(R.string.play_on_tv),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        ControllerPad(
            state = state,
            onConnect = session::connect,
            onProveCode = session::proveCode,
            onJoin = session::join,
            onInput = session::sendInput,
            onLeave = session::leave,
            onRescan = session::rescan,
            modifier = modifier.padding(padding),
        )
    }
}
