package com.vayunmathur.calendar.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.vayunmathur.calendar.wear.R
import com.vayunmathur.calendar.wear.data.WearCalendarStore
import com.vayunmathur.calendar.wear.domain.WearCalendarEvent
import com.vayunmathur.calendar.wear.domain.WearCalendarSnapshot
import com.vayunmathur.calendar.wear.domain.wearAgenda
import com.vayunmathur.calendar.wear.domain.wearEventDay
import com.vayunmathur.calendar.wear.sync.requestWearSync
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime

/** Round-screen agenda: today's events first, then upcoming grouped by day. */
@Composable
fun WearAgendaScreen(store: WearCalendarStore, onOpenEvent: (WearCalendarEvent) -> Unit) {
    val snapshot by store.snapshot.collectAsState()
    val context = LocalContext.current
    WearAgendaContent(
        snapshot = snapshot,
        nowMillis = System.currentTimeMillis(),
        onOpenEvent = onOpenEvent,
        onRequestSync = { requestWearSync(context) },
    )
}

/**
 * Stateless agenda body behind [WearAgendaScreen]: snapshot and instant are parameters,
 * so Compose previews (src/screenshotTest) render literal fixtures with no store or phone.
 */
@Composable
internal fun WearAgendaContent(
    snapshot: WearCalendarSnapshot,
    nowMillis: Long,
    onOpenEvent: (WearCalendarEvent) -> Unit,
    onRequestSync: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var syncNote by remember { mutableStateOf<String?>(null) }
    val requestedText = stringResource(R.string.wear_sync_requested)
    val noPhoneText = stringResource(R.string.wear_no_phone)
    val zone = remember { TimeZone.currentSystemDefault() }
    val agenda = remember(snapshot, nowMillis) {
        wearAgenda(snapshot, nowMillis, zone)
    }
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        syncNote?.let { note ->
            item {
                Text(
                    note,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        if (snapshot.events.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            if (agenda.today.isNotEmpty()) {
                item { WearSectionHeader(stringResource(R.string.wear_today)) }
                items(agenda.today, key = { "${it.eventId}:${it.startMillis}" }) { event ->
                    WearEventChip(event, zone, onClick = { onOpenEvent(event) })
                }
            }
            if (agenda.upcoming.isNotEmpty()) {
                item { WearSectionHeader(stringResource(R.string.wear_upcoming)) }
                agenda.upcoming.groupBy { wearEventDay(it, zone) }.forEach { (day, dayEvents) ->
                    item(key = "day:$day") { WearSectionHeader(formatWearDay(day)) }
                    items(dayEvents, key = { "${it.eventId}:${it.startMillis}" }) { event ->
                        WearEventChip(event, zone, onClick = { onOpenEvent(event) })
                    }
                }
            }
            if (snapshot.truncated) {
                item {
                    Text(
                        stringResource(R.string.wear_truncated),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
        item {
            Chip(
                onClick = {
                    scope.launch {
                        syncNote = if (onRequestSync()) requestedText else noPhoneText
                    }
                },
                label = { Text(stringResource(R.string.wear_request_sync), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

/** One section label in the agenda list. */
@Composable
private fun WearSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** One tappable event row: title plus its time (or All day). */
@Composable
private fun WearEventChip(event: WearCalendarEvent, zone: TimeZone, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(event.title, maxLines = 1) },
        secondaryLabel = { Text(formatWearEventTime(event, zone), maxLines = 1) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

private val wearDayFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    day()
}

/** "Nov 25" style day label. */
fun formatWearDay(date: LocalDate): String = date.format(wearDayFormat)

private val wearTimeFormat = LocalDateTime.Format {
    hour()
    char(':')
    minute()
    char(' ')
    amPmMarker("AM", "PM")
}

/** "All day" or "2:30 PM" for one event in [zone]. */
fun formatWearEventTime(event: WearCalendarEvent, zone: TimeZone): String {
    if (event.allDay) return "All day"
    val local = Instant.fromEpochMilliseconds(event.startMillis).toLocalDateTime(zone)
    return local.format(wearTimeFormat)
}
