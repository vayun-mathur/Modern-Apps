package com.vayunmathur.maps.ui
import kotlinx.coroutines.delay
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberBottomSheetState
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.platform.DeparturesState

/**
 * Fixed height for the board's status states (loading / no departures).
 *
 * Both [LoadingState] and [EmptyState] default to `fillMaxSize`, which inside a
 * `ModalBottomSheet` measures the sheet at full height: the board opens
 * fullscreen while fetching and then snaps down once departures arrive. A
 * bounded status block keeps the sheet at content height through the fetch.
 */
private val BoardStatusHeight = 160.dp

/**
 * Public-transit departure board (P10): a live board for one stop, opened by
 * tapping a transit-stop pin. Departures are grouped by line (each with its GTFS
 * route colour), show a client-side live countdown, and are coloured by delay
 * (on-time green, slightly late amber, very late / cancelled red). Uses the
 * shared [ModalBottomSheet] from `:library:ui` (no raw scaffold), mirroring
 * [ParkingSheet] / [LayersSheet].
 *
 * The live overlay is ONLINE-ONLY (Transitous); a failed fetch serves the
 * last-good board with a stale indication, or an unavailable state with
 * retry when no last-good board exists — never a silent blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeparturesSheet(
    state: DeparturesState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onTrainTap: (Departure) -> Unit = {},
) {
    val stop: TransitStop? = when (state) {
        is DeparturesState.Loading -> state.stop
        is DeparturesState.Loaded -> state.stop
        DeparturesState.Idle -> null
    }

    // Live countdown clock: recomputed every 15 s (minute-granularity display).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffectTicker { now = it }

    // PartiallyExpanded is deliberately not an enabled value. Material3's sheet back handler
    // collapses an Expanded sheet to PartiallyExpanded instead of dismissing whenever that anchor
    // exists, and since Material3 1.5 the anchor is created at every sheet height - for a board
    // shorter than half the screen it sits exactly on top of Expanded, so that back press moves
    // nothing and reads as "back did not work". Refresh is what promotes the sheet to Expanded:
    // the full-height loading state re-anchors the sheet, and growing back out of a collapsed
    // (Expanded == PartiallyExpanded) anchor pair is what Material3 treats as a promotion.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stop?.name ?: stringResource(R.string.transit_departures_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.transit_departures_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) {
                    IconRefresh()
                }
            }

            Spacer(Modifier.height(8.dp))

            when (state) {
                // Loading and Idle were collapsed into one branch, both showing "loading" —
                // so a board that had not been asked for yet claimed to be fetching. Idle is
                // only reachable transiently as the sheet opens, and showing nothing for that
                // frame is more honest than showing a spinner for a request nobody made.
                is DeparturesState.Loading -> LoadingState(
                    modifier = Modifier.height(BoardStatusHeight),
                    message = stringResource(R.string.transit_departures_loading),
                )
                DeparturesState.Idle -> Unit
                is DeparturesState.Loaded -> {
                    when {
                        state.unavailable -> ErrorState(
                            title = stringResource(R.string.transit_departures_unavailable_title),
                            message = stringResource(R.string.transit_departures_unavailable_message),
                            retryLabel = stringResource(R.string.transit_departures_retry),
                            onRetry = onRefresh,
                            modifier = Modifier.height(BoardStatusHeight),
                        )
                        state.departures.isEmpty() -> {
                            if (state.stale) StaleDeparturesBanner(state.fetchedAtMillis)
                            EmptyState(
                                title = stringResource(R.string.transit_departures_none),
                                modifier = Modifier.height(BoardStatusHeight),
                            )
                        }
                        else -> {
                            if (state.stale) StaleDeparturesBanner(state.fetchedAtMillis)
                            DepartureList(state.departures, now, onTrainTap)
                        }
                    }
                }
            }
        }
    }
}

/** Stale indication above a last-good board (or a last-good empty board). */
@Composable
private fun StaleDeparturesBanner(fetchedAtMillis: Long?) {
    Text(
        if (fetchedAtMillis != null) {
            stringResource(R.string.transit_departures_stale_at, DepartureTime.clock(fetchedAtMillis))
        } else {
            stringResource(R.string.transit_departures_stale)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * Every train through this stop, in the order they leave.
 *
 * # Why one chronological list rather than groups per line
 *
 * The board used to group by line, which answers "when is the next Caltrain" but not "what is the
 * next train", and on a platform the second is the question. Grouping also makes opening on the
 * next departure impossible: there is no single next row to scroll to when the list is a set of
 * per-line runs. The line is still on every row, as a badge, so nothing is lost.
 *
 * # The window
 *
 * A day either side of now. Departures already gone are kept — deliberately — because "did I just
 * miss it" is a real question on a platform, and a board that erases the train you were running
 * for cannot answer it. Anything further out is next week's timetable.
 */
@Composable
private fun DepartureList(
    departures: List<Departure>,
    now: Long,
    onTrainTap: (Departure) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the departures rather than on `now`: `now` ticks every 15 s and re-sorting the
    // board on every tick would be wasted work and would fight the scroll position.
    val shown = remember(departures) {
        departures
            .filter { DepartureTime.withinWindow(it.realtimeMillis, now) }
            .sortedBy { it.realtimeMillis }
    }
    val listState = rememberLazyListState()

    // Open on the next train that has not gone, so the board lands where it is useful rather than
    // at whatever left a day ago. Only on open, and only once: re-running this as `now` ticks
    // would drag the list back under the user's finger every fifteen seconds.
    LaunchedEffect(shown) {
        if (shown.isNotEmpty()) {
            listState.scrollToItem(
                DepartureTime.firstUpcoming(shown.map { it.realtimeMillis }, now)
                    .coerceAtMost(shown.lastIndex)
            )
        }
    }

    LazyColumn(modifier.fillMaxWidth().heightIn(max = 420.dp), state = listState) {
        items(shown, key = { "${it.line}|${it.headsign}|${it.scheduledMillis}" }) { dep ->
            DepartureRow(dep, now, onTrainTap)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun DepartureRow(
    dep: Departure,
    now: Long,
    onTrainTap: (Departure) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A row with a pack trip behind it opens that trip's sheet: the same
    // trip a tapped vehicle sprite opens. Online-only rows (no pack trip)
    // stay inert.
    val tap = dep.tripVehicleId?.let { { onTrainTap(dep) } }
    Row(
        (if (tap == null) modifier else modifier.clickable(onClick = tap))
            .fillMaxWidth().padding(vertical = 6.dp).alpha(departedAlpha(dep.realtimeMillis, now)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The badge moved onto the row when the board stopped grouping by line: without it a
        // chronological list gives no way to tell which service a train belongs to.
        LineBadge(dep.line, dep.routeColor)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                dep.headsign.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (dep.cancelled) TextDecoration.LineThrough else null,
            )
            val platform = dep.platform
            if (platform != null) {
                Text(
                    stringResource(R.string.transit_platform, platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            if (dep.cancelled) {
                Text(
                    stringResource(R.string.transit_cancelled),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    countdownText(dep.realtimeMillis, now),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = delayColor(dep.delayMinutes),
                )
                if (dep.realTime && dep.delayMinutes != 0) {
                    Text(
                        if (dep.delayMinutes > 0) {
                            stringResource(R.string.transit_late_minutes, dep.delayMinutes)
                        } else {
                            stringResource(R.string.transit_early_minutes, -dep.delayMinutes)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = delayColor(dep.delayMinutes),
                    )
                }
            }
        }
    }
}

/**
 * How a departure reads: a countdown when it is close, a clock time when it is not.
 *
 * See [DepartureTime] for the rule and its tests. A train that has already gone is shown as
 * "5 min ago" rather than hidden, because "did I just miss it" is a question the board should
 * answer.
 */
@Composable
private fun countdownText(target: Long, now: Long): String =
    when (val label = DepartureTime.label(target, now)) {
        DepartureTime.Label.Now -> stringResource(R.string.transit_now)
        is DepartureTime.Label.InMinutes ->
            stringResource(R.string.transit_minutes, label.minutes.toInt())
        is DepartureTime.Label.MinutesAgo ->
            stringResource(R.string.transit_minutes_ago, label.minutes.toInt())
        is DepartureTime.Label.Clock -> label.text
    }

/** A departure already gone is dimmed, so the eye lands on the next one to catch. */
@Composable
@ReadOnlyComposable
private fun departedAlpha(target: Long, now: Long): Float = if (target < now) 0.5f else 1f

/**
 * On-time/early → muted, 1–4 min late → tertiary, ≥5 late → error.
 *
 * Scheme-derived, not fixed: these sit on the sheet's own surface rather than on the tiles,
 * so they are ordinary UI text and should follow the theme. "On time" is deliberately the
 * quiet one — it is the expected case, and colouring it green made every board look alarming.
 */
@Composable
@ReadOnlyComposable
private fun delayColor(delayMinutes: Int): Color = when {
    delayMinutes <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    delayMinutes < 5 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/** A once-per-15s ticker that reports the current epoch millis to [onTick]. */
@Composable
private fun LaunchedEffectTicker(onTick: (Long) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            onTick(System.currentTimeMillis())
            delay(15_000L)
        }
    }
}
