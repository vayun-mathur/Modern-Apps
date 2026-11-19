package com.vayunmathur.clock.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.platform.ClockUiState
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.ui.draggableHandle
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.toLocalDateTime

/**
 * The clock tab, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockScreen(
    backStack: NavBackStack<Route>,
    state: ClockUiState,
    onReorder: (List<String>) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var localClocks by remember { mutableStateOf(state.worldClocks) }
    var hasDragged by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in localClocks.indices && to.index in localClocks.indices) {
            localClocks = localClocks.toMutableList().apply { add(to.index, removeAt(from.index)) }
            hasDragged = true
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    LaunchedEffect(state.worldClocks) {
        if (!reorderState.isAnyItemDragging) localClocks = state.worldClocks
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && hasDragged) {
            onReorder(localClocks.map { it.city })
            hasDragged = false
        }
    }

    LazyListScaffold(floatingActionButton = {
        FloatingActionButton({
            backStack.add(Route.SelectTimeZonesDialog)
        }) { IconAdd() }
    }, state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            val is24h = state.is24Hour
            val time = state.now().toLocalDateTime(state.zone)
            Row(Modifier.fillParentMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text(DateString.timeSecondsNumeric(time.time, is24h), style = MaterialTheme.typography.displayLarge)
                if (!is24h) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (time.time.hour >= 12) stringResource(R.string.time_pm) else stringResource(R.string.time_am),
                        style = MaterialTheme.typography.displayMedium
                    )
                }
            }
        }
        item {
            val time = state.now().toLocalDateTime(state.zone)
            Text(DateString.dateWeekdayNoYear(time.date), Modifier.fillParentMaxWidth(), textAlign = TextAlign.Center)
        }
        itemsIndexed(localClocks, key = { _, worldClock -> worldClock.city }) { index, worldClock ->
            val timeHere = state.now().toLocalDateTime(worldClock.zone)
            val is24h = state.is24Hour
            val amPm = if (is24h) "" else if (timeHere.time.hour >= 12) stringResource(R.string.time_pm) else stringResource(R.string.time_am)
            ReorderableItem(reorderState, key = worldClock.city, modifier = Modifier.fillParentMaxWidth().animateItem()) { isDragging ->
                val elevation = animatedDp(if (isDragging) 6.dp else 0.dp)
                Card(Modifier.fillMaxWidth().shadow(elevation, MaterialTheme.shapes.medium)) {
                    ListItem({ Text(worldClock.city) }, trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(DateString.timeNumeric(timeHere.time, is24h) + amPm)
                            if (localClocks.size > 1) {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(
                                        reorderState,
                                        key = worldClock.city,
                                        index = index,
                                        onDragStarted = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        },
                                    ),
                                ) { IconDragHandle(tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
    }
}
