package com.vayunmathur.maps.ui.map
import com.vayunmathur.maps.data.string
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.draggableHandle
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.verticalShape
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.R as MapsR

/**
 * The reorderable list of route stops, floating at the top of the map.
 *
 * Each waypoint is a row in one grouped card ([verticalShape]) rather than a separate card, so
 * a three-stop route reads as one route instead of three unrelated places. A null waypoint is
 * the user's own location, which is why the label falls back rather than being blank.
 *
 * Keyed on position string because a waypoint has no id, and two stops at the same coordinate
 * would be the same stop.
 */
@Composable
fun WaypointList(
    route: SpecificFeature.Route,
    onReorder: (SpecificFeature.Route) -> Unit,
    onEditWaypoint: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState, onMove = { from, to ->
        val reordered = route.waypoints.toMutableList()
        val moved = reordered[from.index]
        reordered[from.index] = reordered[to.index]
        reordered[to.index] = moved
        onReorder(route.copy(waypoints = reordered))
    })

    LazyColumn(
        state = listState,
        modifier = modifier.padding(MapChromeMetrics.chromeMargin).fillMaxWidth(),
    ) {
        itemsIndexed(
            route.waypoints,
            key = { _, waypoint -> waypoint?.position?.toString() ?: "" },
        ) { index, waypoint ->
            val key = waypoint?.position?.toString() ?: ""
            ReorderableItem(reorderState = reorderState, key = key) { isDragging ->
                val elevation = animatedDp(if (isDragging) 4.dp else 0.dp)
                Card(
                    shape = verticalShape(index, route.waypoints.size),
                    elevation = CardDefaults.cardElevation(elevation),
                ) {
                    ListItem(
                        { Text(waypoint?.name ?: stringResource(MapsR.string.your_location)) },
                        Modifier.clickable { onEditWaypoint(index) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Only intermediate stops can be removed: the origin and the
                                // destination are what make it a route.
                                if (index > 0 && index < route.waypoints.size - 1) {
                                    IconButton({
                                        val remaining = route.waypoints.toMutableList()
                                        remaining.removeAt(index)
                                        onReorder(route.copy(waypoints = remaining))
                                    }) {
                                        IconClose()
                                    }
                                }
                                IconDragHandle(
                                    Modifier.draggableHandle(reorderState, key = key, index = index)
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(Color.Transparent),
                    )
                }
            }
        }
    }
}
