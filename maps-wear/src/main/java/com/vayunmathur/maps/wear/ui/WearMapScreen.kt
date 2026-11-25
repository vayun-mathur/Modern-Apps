package com.vayunmathur.maps.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.maps.wear.R
import com.vayunmathur.maps.wear.data.WearMapsStore
import com.vayunmathur.maps.wear.domain.wearIsStale
import com.vayunmathur.maps.wear.sync.WearLinkBridge
import kotlinx.coroutines.launch

/**
 * Map home page: the rendered region with the user (and route, when the
 * phone is navigating) on it. Honesty labels sit under the map: offline
 * when the link is down, stale when the snapshot aged out, and a limited-
 * detail note when the phone flagged missing linework.
 */
@Composable
fun WearMapScreen(
    store: WearMapsStore,
    onOpenRoute: () -> Unit,
    onOpenSaved: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()
    val route by store.route.collectAsState()
    val position by store.position.collectAsState()
    val connected by store.connected.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val requestedText = stringResource(R.string.wear_requested)
    val noPhoneText = stringResource(R.string.wear_no_phone)

    Box(Modifier.fillMaxSize()) {
        val current = snapshot
        if (current == null) {
            Text(
                stringResource(R.string.wear_no_region),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.Center),
            )
        } else {
            WearMapCanvas(snapshot = current, route = route, position = position)
            Column(Modifier.align(Alignment.TopCenter).padding(top = 18.dp)) {
                if (route?.active == true) {
                    Text(
                        route!!.nextText.ifBlank { route!!.destinationName },
                        style = MaterialTheme.typography.title3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        maxLines = 2,
                    )
                    Text(
                        "${route!!.distanceToNextM} m · ${(route!!.distanceRemainingM / 1000.0)} km left",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)) {
                val stale = wearIsStale(current, System.currentTimeMillis())
                when {
                    !connected -> Text(
                        stringResource(R.string.wear_offline),
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    stale -> Text(
                        stringResource(R.string.wear_stale),
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (current.degraded.isNotEmpty()) {
                    Text(
                        stringResource(R.string.wear_limited_detail),
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)) {
            Button(onClick = onOpenRoute, modifier = Modifier.padding(end = 4.dp)) {
                Text(stringResource(R.string.wear_route), maxLines = 1)
            }
            Button(onClick = onOpenSaved, modifier = Modifier.padding(end = 4.dp)) {
                Text(stringResource(R.string.wear_saved), maxLines = 1)
            }
            Button(onClick = {
                scope.launch {
                    note = if (WearLinkBridge.requestRegion(context)) requestedText else noPhoneText
                }
            }) {
                Text(stringResource(R.string.wear_request_region), maxLines = 1)
            }
        }
    }
}
