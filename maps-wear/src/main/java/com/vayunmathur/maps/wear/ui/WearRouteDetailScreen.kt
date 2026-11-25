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

/** Route detail page: next maneuver plus the phone's maneuver list, or the no-route honesty. */
@Composable
fun WearRouteDetailScreen(
    store: WearMapsStore,
    onBack: () -> Unit,
) {
    val route by store.route.collectAsState()
    WearRouteDetailContent(route = route, onBack = onBack)
}

/** Stateless route detail behind [WearRouteDetailScreen]: route is a parameter for previews. */
@Composable
internal fun WearRouteDetailContent(route: com.vayunmathur.maps.wear.domain.WearRouteState?, onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.wear_maneuvers),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        val current = route
        if (current?.active != true) {
            item {
                Text(
                    stringResource(R.string.wear_no_route),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            if (current.destinationName.isNotBlank()) {
                item {
                    Text(
                        current.destinationName,
                        style = MaterialTheme.typography.title3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }
            items(current.maneuvers) { maneuver ->
                Chip(
                    onClick = {}, enabled = false,
                    label = { Text(maneuver.text.ifBlank { maneuver.kind }, maxLines = 2) },
                    secondaryLabel = { Text("${maneuver.distanceFromStartM} m", maxLines = 1) },
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
