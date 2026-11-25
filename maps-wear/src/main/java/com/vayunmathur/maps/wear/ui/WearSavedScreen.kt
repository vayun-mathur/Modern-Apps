package com.vayunmathur.maps.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
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
import com.vayunmathur.maps.wear.R
import com.vayunmathur.maps.wear.data.WearMapsStore
import com.vayunmathur.maps.wear.domain.wearDistanceM

/**
 * Saved places glance: name + distance from the user (phone position, or
 * the region centre when no fix yet). The phone has no Home/Work concept
 * and no watch ETA is invented — distance only.
 */
@Composable
fun WearSavedScreen(
    store: WearMapsStore,
    onBack: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()
    val position by store.position.collectAsState()
    WearSavedContent(snapshot = snapshot, position = position, onBack = onBack)
}

/** Stateless saved places behind [WearSavedScreen]: snapshot/position are parameters for previews. */
@Composable
internal fun WearSavedContent(snapshot: com.vayunmathur.maps.wear.domain.WearMapSnapshot?, position: com.vayunmathur.maps.wear.domain.WearPosition?, onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.wear_saved),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        val places = snapshot?.saved.orEmpty()
        if (places.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_no_saved),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            val fromLat = position?.latE7 ?: snapshot!!.centerLatE7
            val fromLon = position?.lonE7 ?: snapshot!!.centerLonE7
            items(places) { place ->
                val meters = wearDistanceM(fromLat, fromLon, place)
                Chip(
                    onClick = {}, enabled = false,
                    label = { Text(place.name, maxLines = 1) },
                    secondaryLabel = {
                        Text(
                            if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "${meters.toInt()} m",
                            maxLines = 1,
                        )
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
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
