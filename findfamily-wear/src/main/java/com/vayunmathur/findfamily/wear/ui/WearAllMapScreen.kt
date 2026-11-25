package com.vayunmathur.findfamily.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.findfamily.wear.R
import com.vayunmathur.findfamily.wear.data.WearFindFamilyStore
import com.vayunmathur.findfamily.wear.domain.wearFormatDistance
import com.vayunmathur.findfamily.wear.domain.wearSnapshotIsStale

/**
 * All-people relative map: every located person plotted around the
 * snapshot region centre (you when located), markers keyed by initial
 * in the legend below. No basemap is drawn — relative position only,
 * with snapshot staleness labelled.
 */
@Composable
fun WearAllMapScreen(
    store: WearFindFamilyStore,
    onOpenPerson: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()
    val connected by store.connected.collectAsState()
    WearAllMapContent(snapshot = snapshot, connected = connected, nowMillis = System.currentTimeMillis(), onOpenPerson = onOpenPerson, onBack = onBack)
}

/** Stateless all-map behind [WearAllMapScreen]: snapshot/connection/instant are parameters for previews. */
@Composable
internal fun WearAllMapContent(
    snapshot: com.vayunmathur.findfamily.wear.domain.WearFindFamilySnapshot,
    connected: Boolean,
    nowMillis: Long,
    onOpenPerson: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val now = nowMillis
    val listState = rememberScalingLazyListState()
    val located = snapshot.people.filter { it.latE7 != null && it.lonE7 != null }

    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.wear_map),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (!connected || wearSnapshotIsStale(snapshot, now)) {
            item {
                Text(
                    if (!connected) stringResource(R.string.wear_offline)
                    else stringResource(R.string.wear_stale_snapshot),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        item {
            Text(
                "Within ${wearFormatDistance(snapshot.region.radiusM.toDouble())} of centre",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (located.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_no_location),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            item {
                Box(Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 4.dp)) {
                    WearRelativeCanvas(
                        region = snapshot.region,
                        people = located,
                        nowMillis = now,
                    )
                }
            }
            items(located, key = { it.id }) { person ->
                Chip(
                    onClick = { onOpenPerson(person.id) },
                    label = {
                        Text("${wearMarkerInitial(person.name)} — ${person.name}", maxLines = 1)
                    },
                    secondaryLabel = {
                        Text(person.placeLabel.ifBlank { stringResource(R.string.wear_no_location) }, maxLines = 1)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        item {
            Chip(
                onClick = onBack,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
