package com.vayunmathur.maps.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Column
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
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
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
    WearMapContent(snapshot = snapshot, route = route, position = position, connected = connected, nowMillis = System.currentTimeMillis(), onOpenRoute = onOpenRoute, onOpenSaved = onOpenSaved, onRequestRegion = { WearLinkBridge.requestRegion(context) })
}

/** Stateless map behind [WearMapScreen]: snapshot/route/position are parameters for previews. */
@Composable
internal fun WearMapContent(
    snapshot: com.vayunmathur.maps.wear.domain.WearMapSnapshot?,
    route: com.vayunmathur.maps.wear.domain.WearRouteState?,
    position: com.vayunmathur.maps.wear.domain.WearPosition?,
    connected: Boolean,
    nowMillis: Long,
    onOpenRoute: () -> Unit,
    onOpenSaved: () -> Unit,
    onRequestRegion: suspend () -> Boolean,
) {
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
        }
        // One chip row on the centreline, lifted 22dp: the previous row of
        // circular Buttons at 2dp put text in the bezel corners on round watches,
        // and three stacked full-width chips would cover the centred user puck.
        Column(
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 22.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Honesty labels stack directly above the chips so neither sits
            // in the bezel and the centred user puck stays clear.
            val currentSnapshot = snapshot
            if (currentSnapshot != null) {
                val stale = wearIsStale(currentSnapshot, nowMillis)
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
                if (currentSnapshot.degraded.isNotEmpty()) {
                    Text(
                        stringResource(R.string.wear_limited_detail),
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LazyRow {
                item {
                    Chip(
                        onClick = onOpenRoute,
                        label = { Text(stringResource(R.string.wear_route), maxLines = 1) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
                item {
                    Chip(
                        onClick = onOpenSaved,
                        label = { Text(stringResource(R.string.wear_saved), maxLines = 1) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
                item {
                    Chip(
                        onClick = {
                            scope.launch {
                                note = if (onRequestRegion()) requestedText else noPhoneText
                            }
                        },
                        label = { Text(stringResource(R.string.wear_request_region), maxLines = 1) },
                        colors = ChipDefaults.primaryChipColors(),
                    )
                }
            }
        }
    }
}
