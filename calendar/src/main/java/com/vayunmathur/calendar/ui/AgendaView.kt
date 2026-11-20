package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedText
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

/** Total scrollable days; index [AGENDA_INITIAL_INDEX] is the anchor day. */
internal const val AGENDA_TOTAL_DAYS = 100_000

/** Index of the anchor day; the list can scroll ~137 years either way. */
internal const val AGENDA_INITIAL_INDEX = 50_000

/**
 * Days per instance-load page. The visible window is quantized to whole pages,
 * so scrolling within a page never re-queries the provider: one [loadInstances]
 * call serves every day on the page instead of one call per composed day.
 */
internal const val AGENDA_PAGE_DAYS = 31

/** Extra days loaded beyond the visible items on each side of the window. */
internal const val AGENDA_PREFETCH_DAYS = 10

/** Content type shared by every agenda day row (all rows have identical structure). */
internal const val AGENDA_DAY_CONTENT_TYPE = "agenda_day"

/**
 * Item indices whose instances must be loaded for a list currently showing
 * [firstVisible]..[lastVisible]: the visible span widened by
 * [AGENDA_PREFETCH_DAYS] on both sides, then rounded outward to whole
 * [AGENDA_PAGE_DAYS] pages and clamped to the list bounds.
 */
internal fun agendaWindowIndices(firstVisible: Int, lastVisible: Int): IntRange {
    val from = firstVisible - AGENDA_PREFETCH_DAYS
    val to = lastVisible + AGENDA_PREFETCH_DAYS
    val start = (from / AGENDA_PAGE_DAYS) * AGENDA_PAGE_DAYS
    val end = ((to / AGENDA_PAGE_DAYS) + 1) * AGENDA_PAGE_DAYS - 1
    return start.coerceAtLeast(0)..end.coerceAtMost(AGENDA_TOTAL_DAYS - 1)
}

/**
 * Expands [instances] into per-day lists for the window
 * [windowStart, windowEndExclusive): each instance is filed under every one of
 * its span days inside the window, sorted by start time within each day. Days
 * with no instances are absent (callers use an empty list). Doing the span-day
 * expansion once here keeps the per-item filter out of composition.
 */
internal fun groupInstancesByDay(
    instances: List<Instance>,
    windowStart: LocalDate,
    windowEndExclusive: LocalDate,
): Map<LocalDate, List<Instance>> {
    val byDay = mutableMapOf<LocalDate, MutableList<Instance>>()
    for (instance in instances.sortedBy { it.startDateTime }) {
        for (day in instance.spanDays) {
            if (day < windowStart || day >= windowEndExclusive) continue
            byDay.getOrPut(day) { mutableListOf() }.add(instance)
        }
    }
    return byDay
}

@Composable
fun AgendaView(
    context: android.content.Context,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    anchorDate: LocalDate,
    dateViewing: LocalDate,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit
) {
    val initialIndex = AGENDA_INITIAL_INDEX
    val listState = rememberLazyListState(initialIndex)
    // New rows have no id yet and can't be looked up; drop them instead of crashing.
    val vEventsByID = remember(events) { events.mapNotNull { e -> e.id?.let { id -> id to e } }.toMap() }
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    // The quantized window of item indices to have instances loaded for. Derived
    // from the layout info so it only changes when scrolling crosses a page
    // boundary, not on every composed day or scroll frame. Before the first
    // layout there are no visible items yet; centre on the initial index.
    val windowIndices by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                agendaWindowIndices(initialIndex, initialIndex)
            } else {
                agendaWindowIndices(visible.first().index, visible.last().index)
            }
        }
    }
    val windowStart = anchorDate.plus(DatePeriod(days = windowIndices.first - initialIndex))
    val windowEndExclusive = anchorDate.plus(DatePeriod(days = windowIndices.last + 1 - initialIndex))

    // One provider query per window (per events/visibility change), grouped by
    // day up front. The previous per-item produceState ran one query per
    // composed day — a day spanning the viewport edge plus prefetch rows meant
    // ~a dozen overlapping queries for what is now a single window query.
    val instancesByDay by produceState<Map<LocalDate, List<Instance>>>(
        emptyMap(),
        windowStart,
        windowEndExclusive,
        events,
        calendarVisibility,
    ) {
        val loaded = loadInstances(
            windowStart.atStartOfDayIn(TimeZone.currentSystemDefault()),
            windowEndExclusive.atStartOfDayIn(TimeZone.currentSystemDefault()),
        )
        value = groupInstancesByDay(loaded, windowStart, windowEndExclusive)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val date = anchorDate.plus(DatePeriod(days = index - initialIndex))
            if (date != dateViewing) {
                onDateViewingChanged(date)
            }
        }
    }

    LaunchedEffect(dateViewing) {
        if (!listState.isScrollInProgress) {
            val targetIndex = initialIndex + (dateViewing.toEpochDays() - anchorDate.toEpochDays()).toInt()
            if (listState.firstVisibleItemIndex != targetIndex) {
                listState.scrollToItem(targetIndex)
            }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(
            count = AGENDA_TOTAL_DAYS,
            key = { index -> anchorDate.plus(DatePeriod(days = index - initialIndex)).toEpochDays() },
            contentType = { AGENDA_DAY_CONTENT_TYPE },
        ) { index ->
            val date = anchorDate.plus(DatePeriod(days = index - initialIndex))
            val dayInstances = instancesByDay[date].orEmpty()
            val isToday = date == today

            Column(Modifier.fillMaxWidth().then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)) else Modifier)) {
                Text(
                    text = DateString.dateWeekday(date),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                dayInstances.forEach { instance ->
                    // Skip instances whose event was just deleted (sync-adapter delete
                    // semantics) instead of crashing.
                    val ev = vEventsByID[instance.eventID] ?: return@forEach
                    val titleKey = eventTitleMorphKey(instance, date)
                    ListItem(
                        content = {
                            Text(
                                ev.title.ifEmpty { context.getString(R.string.no_title) },
                                modifier = if (titleKey == null) Modifier else Modifier.sharedText(titleKey)
                            )
                        },
                        supportingContent = {
                            Text(dateRangeString(context, instance.startDateTimeDisplay.date, instance.endDateTimeDisplay.date, instance.startDateTimeDisplay.time, instance.endDateTimeDisplay.time, instance.allDay, includeDate = false))
                        },
                        leadingContent = {
                            Box(Modifier.size(16.dp).background(Color(ev.color ?: calendars[ev.calendarID]?.color ?: 0xFF808080.toInt()), CircleShape))
                        },
                        modifier = Modifier.clickable { onEventClick(instance) }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
