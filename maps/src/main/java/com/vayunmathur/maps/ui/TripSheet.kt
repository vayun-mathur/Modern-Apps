package com.vayunmathur.maps.ui
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberBottomSheetState
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.transit.TripItinerary
import com.vayunmathur.maps.data.transit.TripStop
import com.vayunmathur.maps.platform.TripItineraryState

/**
 * Fixed height for the sheet's status states (loading / missing trip).
 *
 * Both the loading text and [EmptyState] default to `fillMaxSize`, which inside a
 * `ModalBottomSheet` measures the sheet at full height: the sheet opens
 * fullscreen while fetching and then snaps down once the itinerary arrives. A
 * bounded status block keeps the sheet at content height through the fetch.
 */
private val BoardStatusHeight = 160.dp

/**
 * A vehicle's full run: every station it was at or will be at, and when.
 *
 * Opened by tapping a vehicle sprite on the map, or a train on a departure
 * board (both resolve to the same pack trip). Times are countdowns like the
 * board, so no timezone applies. Tapping a station opens its departure
 * board; the trip sheet is replaced rather than stacked (one tap, one back).
 *
 * Mirrors [DeparturesSheet]: the same [ModalBottomSheet], the same 15 s
 * countdown ticker, the same cancelled styling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripSheet(
    state: TripItineraryState,
    onDismiss: () -> Unit,
    onStopTap: (TripStop) -> Unit,
) {
    val itinerary: TripItinerary? = (state as? TripItineraryState.Loaded)?.itinerary

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DeparturesTicker { now = it }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            when (state) {
                TripItineraryState.Idle -> Unit
                TripItineraryState.Loading -> Text(
                    stringResource(R.string.transit_departures_loading),
                    modifier = Modifier.height(BoardStatusHeight),
                )
                TripItineraryState.Missing -> EmptyState(
                    title = stringResource(R.string.transit_departures_none),
                    modifier = Modifier.height(BoardStatusHeight),
                )
                is TripItineraryState.Loaded -> {
                    val itin = state.itinerary
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LineBadge(itin.routeName, itin.routeColor)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    itin.headsign.ifBlank { itin.routeName },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                stringResource(R.string.transit_trip_title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (itin.cancelled) {
                            Text(
                                stringResource(R.string.transit_cancelled),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TripStopList(itin, now, onStopTap)
                }
            }
        }
    }
}

/**
 * Every station on the run in travel order, scrolled to the next one the
 * trip has not yet served. Past stations dim; the one being served reads
 * "now". A tap opens that station's departure board.
 */
@Composable
private fun TripStopList(
    itinerary: TripItinerary,
    now: Long,
    onStopTap: (TripStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stops = itinerary.stops
    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(stops) {
        if (stops.isNotEmpty()) {
            val next = stops.indexOfFirst { it.departsMillis >= now }.coerceAtLeast(0)
            listState.scrollToItem(next.coerceAtMost(stops.lastIndex))
        }
    }
    LazyColumn(modifier.fillMaxWidth().heightIn(max = 420.dp), state = listState) {
        items(stops, key = { "${it.name}|${it.departsMillis}" }) { stop ->
            TripStopRow(stop, now, onStopTap)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun TripStopRow(
    stop: TripStop,
    now: Long,
    onStopTap: (TripStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clickable { onStopTap(stop) }.padding(vertical = 6.dp)
            .alpha(departedStopAlpha(stop.departsMillis, now)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stop.name.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tripStopTime(stop, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Arrival when dwelling or done, departure when still to come. */
@Composable
@ReadOnlyComposable
private fun tripStopTime(stop: TripStop, now: Long): String {
    val target = if (now < stop.departsMillis) stop.departsMillis else stop.arrivesMillis
    return when (val label = DepartureTime.label(target, now)) {
        DepartureTime.Label.Now -> stringResource(R.string.transit_now)
        is DepartureTime.Label.InMinutes ->
            stringResource(R.string.transit_minutes, label.minutes.toInt())
        is DepartureTime.Label.MinutesAgo ->
            stringResource(R.string.transit_minutes_ago, label.minutes.toInt())
        is DepartureTime.Label.Clock -> label.text
    }
}

/** A stop already served dims, so the eye lands on the next one to catch. */
@Composable
@ReadOnlyComposable
private fun departedStopAlpha(target: Long, now: Long): Float = if (target < now) 0.5f else 1f

/** The 15 s countdown ticker, shared with the departure board's cadence. */
@Composable
private fun DeparturesTicker(onTick: (Long) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            onTick(System.currentTimeMillis())
            kotlinx.coroutines.delay(15_000L)
        }
    }
}
