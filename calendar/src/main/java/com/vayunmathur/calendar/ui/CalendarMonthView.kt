package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localeFirstDayOfWeek
import com.vayunmathur.library.util.sharedText
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlin.time.Instant

/**
 * Month grid. [today] doubles as the pager anchor: page 5000 is the month containing it,
 * and it is the day drawn with the "today" highlight.
 */
@Composable
fun MonthCalendarView(
    context: android.content.Context,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    today: LocalDate,
    dateViewing: LocalDate,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit,
    /**
     * Pre-resolved instances, used instead of querying through [loadInstances]. Only a
     * preview passes this — see [com.vayunmathur.calendar.platform.CalendarUiState].
     */
    previewInstances: List<Instance>? = null,
) {
    val anchorDate = today
    val pagerState = rememberPagerState(initialPage = 5000) { 10000 }
    var programmaticScroll by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        if (programmaticScroll) return@LaunchedEffect
        // Report the visible month, derived from its first day (see monthFirstDay):
        // adding months to the anchor directly overflows on a 31st (Jan 31 + 1 mo),
        // shifting the pager and the title onto the wrong month.
        onDateViewingChanged(monthDateClamped(anchorDate, pagerState.currentPage))
    }

    LaunchedEffect(dateViewing) {
        if (!pagerState.isScrollInProgress) {
            val monthsDiff = (dateViewing.year * 12 + dateViewing.month.number) - (anchorDate.year * 12 + anchorDate.month.number)
            val targetPage = 5000 + monthsDiff
            if (pagerState.currentPage != targetPage) {
                programmaticScroll = true
                pagerState.scrollToPage(targetPage)
                programmaticScroll = false
            }
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val firstOfMonth = monthFirstDay(anchorDate, page)
        val lastOfMonth = firstOfMonth.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))

        val locale = context.resources.configuration.locales[0]
        val firstDayOfWeek = localeFirstDayOfWeek(locale)
        val lastDayOfWeek = if (firstDayOfWeek == 1) 7 else firstDayOfWeek - 1

        val startDay = firstOfMonth.minus(DatePeriod(days = firstDayOfWeekOffset(firstOfMonth, locale)))
        val endDay = lastOfMonth.plus(DatePeriod(days = (lastDayOfWeek - lastOfMonth.dayOfWeek.isoDayNumber + 7) % 7))

        val weeks = remember(startDay, endDay) {
            buildList {
                var curr = startDay
                while (curr <= endDay) {
                    add(curr)
                    curr = curr.plus(DatePeriod(days = 7))
                }
            }
        }

        // New rows have no id yet and can't be looked up; drop them instead of crashing.
        val vEventsByID = remember(events) { events.mapNotNull { e -> e.id?.let { id -> id to e } }.toMap() }
        val loadedInstances by produceState(emptyList<Instance>(), events, calendarVisibility, startDay, endDay) {
            value = loadInstances(
                startDay.atStartOfDayIn(TimeZone.currentSystemDefault()),
                endDay.atEndOfDayIn(TimeZone.currentSystemDefault())
            )
        }
        val monthInstances = previewInstances ?: loadedInstances

        Column(Modifier.fillMaxSize().padding(4.dp), Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
                val headerStart = weeks.first()
                for (i in 0..6) {
                    val d = headerStart.plus(DatePeriod(days = i))
                    Text(
                        localizedDayOfWeekNames(DateNameStyle.SHORT)[d.dayOfWeek.isoDayNumber - 1],
                        Modifier.weight(1f),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            weeks.forEach { weekSunday ->
                MonthWeekRow(
                    Modifier.weight(1f),
                    weekSunday,
                    vEventsByID,
                    calendars,
                    onEventClick,
                    onDayClick,
                    onDayLongClick,
                    context,
                    firstOfMonth.month.number,
                    monthInstances,
                    today
                )
            }
        }
    }
}

/**
 * The first day of the month shown on [page], computed from a day-1 base so month
 * arithmetic can never overflow: `anchorDate.plus(DatePeriod(months = n))` clamps a 31st
 * anchor (Jan 31 + 1 month lands Feb 28), which is the right month here but only by
 * accident of clamping — deriving from the 1st makes it structural.
 */
private fun monthFirstDay(anchorDate: LocalDate, page: Int): LocalDate =
    LocalDate(anchorDate.year, anchorDate.month, 1).plus(DatePeriod(months = page - 5000))

/** Same month as [monthFirstDay], keeping the anchor's day when that month has it. */
private fun monthDateClamped(anchorDate: LocalDate, page: Int): LocalDate {
    val first = monthFirstDay(anchorDate, page)
    val lastDay = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).day
    return LocalDate(first.year, first.month, anchorDate.day.coerceAtMost(lastDay))
}

@Composable
internal fun MonthWeekRow(
    modifier: Modifier,
    weekSunday: LocalDate,
    vEventsByID: Map<Long, Event>,
    calendars: Map<Long, Calendar>,
    onEventClick: (Instance) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onDayLongClick: (LocalDate) -> Unit,
    context: android.content.Context,
    viewingMonth: Int,
    allInstances: List<Instance>,
    today: LocalDate
) {
    val weekDays = (0..6).map { weekSunday.plus(DatePeriod(days = it)) }

    Row(modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), Arrangement.spacedBy(4.dp)) {
        weekDays.forEach { date ->
            val dayInstances = allInstances.filter { date in it.spanDays }
                .sortedBy { it.startDateTime }
            val isToday = date == today
            val isPartOfViewingMonth = date.month.number == viewingMonth

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                    .combinedClickable(
                        onClick = { onDayClick(date) },
                        onLongClick = { onDayLongClick(date) },
                    )
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isToday) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = date.day.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = date.day.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPartOfViewingMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isPartOfViewingMonth) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Spacer(Modifier.height(2.dp))
                dayInstances.forEach { instance ->
                    // The provider can return instances whose event just got deleted
                    // (sync-adapter delete semantics). Skip the stale row instead of crashing.
                    val ev = vEventsByID[instance.eventID] ?: return@forEach
                    SummaryEventItem(context, instance, ev, calendars, onEventClick, eventTitleMorphKey(instance, date))
                }
            }
        }
    }
}
