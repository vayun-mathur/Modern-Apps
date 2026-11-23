package com.vayunmathur.maps.ui
import kotlinx.coroutines.delay
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ErrorState
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
 * Both the loading text and [EmptyState] default to `fillMaxSize`, which inside a
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
                is DeparturesState.Loading -> Text(
                    stringResource(R.string.transit_departures_loading),
                    modifier = Modifier.height(BoardStatusHeight),
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

/** A once-per-15s ticker that reports the current epoch millis to [onTick]. */
@Composable
private fun LaunchedEffectTicker(onTick: (Long) -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            onTick(System.currentTimeMillis())
            delay(15_000L)
        }
    }
}
