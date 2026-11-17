package com.vayunmathur.share.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.share.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.share.network.transport.Connection
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.SendUiState

/**
 * The whole in-app surface: sending.
 *
 * Receiving has no screen. It is driven by notifications and turned on and off from a Quick
 * Settings tile, so it works when the app has never been opened.
 */
@Composable
fun ShareSendPage(viewModel: ShareViewModel) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        // Letterboxed on expanded windows so the cards keep a readable measure.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            ShareSendScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}





/**
 * Collects a live [Connection]'s flows and hands them to [TransferCard].
 *
 * Split so the rendering half is drivable from literal data: a `Connection` owns a TCP socket
 * and a native session handle, and Layoutlib can load neither.
 */
@Composable
internal fun TransferCardHost(conn: Connection, onDisconnect: () -> Unit) {
    val state by conn.state.collectAsState()
    val error by conn.error.collectAsState()
    val sent by conn.bytesSent.collectAsState()
    val peerName by conn.peerName.collectAsState()
    TransferCard(
        endpoint = peerName ?: conn.remoteEndpoint,
        state = state,
        bytesSent = sent,
        error = error,
        onDisconnect = onDisconnect,
    )
}

