package com.vayunmathur.calendar.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.calendar.wear.R
import com.vayunmathur.calendar.wear.data.WearCalendarStore
import com.vayunmathur.calendar.wear.domain.WearCalendarEvent
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime

/** Read-only detail for one occurrence: title, time, location, notes. No editing — see AGENTS.md. */
@Composable
fun WearEventScreen(
    store: WearCalendarStore,
    eventId: Long,
    startMillis: Long,
    onBack: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()
    val event = snapshot.events.firstOrNull {
        it.eventId == eventId && it.startMillis == startMillis
    }
    WearEventContent(event = event, onBack = onBack)
}

/**
 * Stateless event detail behind [WearEventScreen]: the event is a parameter, so
 * Compose previews (src/screenshotTest) render a literal fixture with no store.
 */
@Composable
internal fun WearEventContent(event: WearCalendarEvent?, onBack: () -> Unit) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(state = listState) {
        if (event == null) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            item {
                Text(
                    event.title,
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            item {
                val day = Instant.fromEpochMilliseconds(event.startMillis)
                    .toLocalDateTime(if (event.allDay) TimeZone.UTC else zone).date
                val timeText = if (event.allDay) {
                    stringResource(R.string.wear_all_day)
                } else {
                    val endEvent = event.copy(startMillis = event.endMillis)
                    "${formatWearEventTime(event, zone)} – ${formatWearEventTime(endEvent, zone)}"
                }
                Text(
                    "${formatWearDay(day)} · $timeText",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (event.location.isNotBlank()) {
                item {
                    Text(
                        event.location,
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (event.calendarName.isNotBlank()) {
                item {
                    Text(
                        event.calendarName,
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (event.description.isNotBlank()) {
                item {
                    Text(
                        event.description,
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
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
