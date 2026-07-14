package com.vayunmathur.calendar.ui

import android.text.format.DateFormat
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.format
import kotlinx.datetime.todayIn
import androidx.core.text.util.LocalePreferences
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun getFirstDayOfWeekValue(locale: Locale): Int {
    return when (LocalePreferences.getFirstDayOfWeek(locale)) {
        LocalePreferences.FirstDayOfWeek.MONDAY -> 1
        LocalePreferences.FirstDayOfWeek.TUESDAY -> 2
        LocalePreferences.FirstDayOfWeek.WEDNESDAY -> 3
        LocalePreferences.FirstDayOfWeek.THURSDAY -> 4
        LocalePreferences.FirstDayOfWeek.FRIDAY -> 5
        LocalePreferences.FirstDayOfWeek.SATURDAY -> 6
        LocalePreferences.FirstDayOfWeek.SUNDAY -> 7
        else -> 7
    }
}

private fun firstDayOfWeekOffset(date: LocalDate, locale: Locale): Int {
    val firstDayOfWeek = getFirstDayOfWeekValue(locale)
    return (date.dayOfWeek.isoDayNumber - firstDayOfWeek + 7) % 7
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: CalendarViewModel, backStack: NavBackStack<Route>) {
    val context = LocalContext.current

    val events by viewModel.events.collectAsStateWithLifecycle()
    val calendarsList by viewModel.calendars.collectAsStateWithLifecycle()
    val calendars = remember(calendarsList) { calendarsList.associateBy { it.id } }
    val calendarVisibility by viewModel.calendarVisibility.collectAsStateWithLifecycle()
    val currentLayout by viewModel.currentLayout.collectAsStateWithLifecycle()

    // currently-viewed date is owned by the VM (initialized from persisted
    // last_viewed_date in DataStore, or today when absent).
    val dateViewing by viewModel.selectedDate.collectAsStateWithLifecycle()

    // Stable anchor for pagers/scrollers — always use today so the initial
    // page offset is correct regardless of any stale persisted date.
    val anchorDate = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    // shared vertical scroll so hour labels and grid scroll together
    val verticalState = rememberScrollState()

    ResultEffect<LocalDate>("GotoDate") { result ->
        viewModel.setSelectedDate(result)
    }

    Scaffold(
        Modifier,
        {
            TopAppBar(
                {
                    // show month/year of the currently visible date
                    val mon = MonthNames.ENGLISH_ABBREVIATED.names[dateViewing.month.number - 1]
                    Row(
                        Modifier.clickable { backStack.add(Route.Calendar.GotoDialog(dateViewing)) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.month_year_format, mon, dateViewing.year), fontWeight = FontWeight.Bold)
                        Icon(painterResource(R.drawable.arrow_drop_down_24px), null)
                    }
                }, actions = {
                    var showLayoutMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { showLayoutMenu = true }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(currentLayout.shortName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Icon(painterResource(R.drawable.arrow_drop_down_24px), null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        DropdownMenu(expanded = showLayoutMenu, onDismissRequest = { showLayoutMenu = false }) {
                            CalendarViewModel.CalendarLayout.entries.forEach { layout ->
                                DropdownMenuItem(
                                    text = { Text(layout.prettyName) },
                                    onClick = {
                                        viewModel.setLayout(layout)
                                        showLayoutMenu = false
                                    }
                                )
                            }
                        }
                    }

                    IconButton({ backStack.add(Route.Settings) }) {
                        IconSettings()
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton({
                // persist currently viewed date before navigating to the new event page
                viewModel.setLastViewedDate(dateViewing)
                backStack.add(Route.EditEvent(null))
            }) {
                IconAdd()
            }
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            when (currentLayout) {
                CalendarViewModel.CalendarLayout.Agenda -> AgendaView(context, events, calendars, calendarVisibility, anchorDate, dateViewing, viewModel::visibleInstances, onEventClick = {
                    viewModel.setLastViewedDate(dateViewing)
                    backStack.add(Route.Event(it))
                }, onDateViewingChanged = { viewModel.setSelectedDate(it) })
                CalendarViewModel.CalendarLayout.Month -> MonthView(context, events, calendars, calendarVisibility, anchorDate, dateViewing, viewModel::visibleInstances, onEventClick = {
                    viewModel.setLastViewedDate(dateViewing)
                    backStack.add(Route.Event(it))
                }, onDayClick = {
                    viewModel.setSelectedDate(it)
                }, onDateViewingChanged = { viewModel.setSelectedDate(it) })
                else -> {
                    CalendarPagerView(
                        context,
                        currentLayout,
                        anchorDate,
                        dateViewing,
                        events,
                        calendars,
                        calendarVisibility,
                        verticalState,
                        viewModel::visibleInstances,
                        onEventClick = {
                            viewModel.setLastViewedDate(dateViewing)
                            backStack.add(Route.Event(it))
                        },
                        onDateViewingChanged = { viewModel.setSelectedDate(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun CalendarPagerView(
    context: android.content.Context,
    currentLayout: CalendarViewModel.CalendarLayout,
    anchorDate: LocalDate,
    dateViewing: LocalDate,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    verticalState: ScrollState,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit
) {
    val daysToShow = when (currentLayout) {
        CalendarViewModel.CalendarLayout.Day -> 1
        CalendarViewModel.CalendarLayout.WorkWeek, CalendarViewModel.CalendarLayout.WorkWeekSummary -> 5
        else -> 7
    }
    val isSummary = currentLayout == CalendarViewModel.CalendarLayout.WorkWeekSummary || 
                    currentLayout == CalendarViewModel.CalendarLayout.FullWeekSummary
    
    val pagerState = rememberPagerState(initialPage = 5000) { 10000 }
    // Track whether the pager is being programmatically scrolled to avoid feedback loops
    var programmaticScroll by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage, currentLayout) {
        if (programmaticScroll) return@LaunchedEffect
        val delta = pagerState.currentPage - 5000
        val currentStart = if (daysToShow == 1) {
            anchorDate.plus(DatePeriod(days = delta))
        } else {
            anchorDate.plus(DatePeriod(days = delta * 7))
        }
        onDateViewingChanged(currentStart)
    }

    LaunchedEffect(dateViewing) {
        if (!pagerState.isScrollInProgress) {
            val delta = if (daysToShow == 1) {
                dateViewing.toEpochDays() - anchorDate.toEpochDays()
            } else {
                (dateViewing.toEpochDays() - anchorDate.toEpochDays()) / 7
            }
            val targetPage = 5000 + delta.toInt()
            if (pagerState.currentPage != targetPage) {
                programmaticScroll = true
                pagerState.scrollToPage(targetPage)
                programmaticScroll = false
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        var yOffset by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current

        if (!isSummary) {
            // Static Hour labels column
            Column {
                Spacer(Modifier.height(yOffset))
                Column(
                    Modifier
                        .verticalScroll(verticalState)
                        .padding(bottom = 16.dp)
                ) {
                    for (hour in 0..23) {
                        Box(modifier = Modifier
                            .height(56.dp)
                            .width(56.dp)) {
                            val hourString = if(DateFormat.is24HourFormat(context)) {
                                "%02d:00".format(hour)
                            } else when {
                                hour == 0 -> context.getString(R.string.twelve_am)
                                hour < 12 -> context.getString(R.string.hour_am, hour)
                                hour == 12 -> context.getString(R.string.twelve_pm)
                                else -> context.getString(R.string.hour_pm, hour - 12)
                            }
                            Text(
                                text = hourString,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 1
        ) { page ->
            val delta = page - 5000
            val pageStartDate = if (daysToShow == 1) {
                anchorDate.plus(DatePeriod(days = delta))
            } else {
                anchorDate.plus(DatePeriod(days = delta * 7))
            }
            
            val startDay = when (currentLayout) {
                CalendarViewModel.CalendarLayout.Day -> pageStartDate
                CalendarViewModel.CalendarLayout.WorkWeek, CalendarViewModel.CalendarLayout.WorkWeekSummary -> 
                    pageStartDate.minus(DatePeriod(days = (pageStartDate.dayOfWeek.isoDayNumber - 1) % 7))
                else -> {
                    val locale = context.resources.configuration.locales[0]
                    pageStartDate.minus(DatePeriod(days = firstDayOfWeekOffset(pageStartDate, locale)))
                }
            }
            
            val weekDays = (0 until daysToShow).map { startDay.plus(DatePeriod(days = it)) }
            val vEventsByID = remember(events) { events.associateBy { it.id!! } }

            val weekInstances by produceState(emptyList<Instance>(), events, calendarVisibility, startDay, daysToShow) {
                value = loadInstances(
                    weekDays.first().atStartOfDayIn(TimeZone.currentSystemDefault()),
                    weekDays.last().atEndOfDayIn(TimeZone.currentSystemDefault())
                )
            }

            Column(Modifier.fillMaxSize()) {
                WeekHeader(weekDays)
                
                if (isSummary) {
                    SummaryGrid(context, weekInstances, vEventsByID, calendars, weekDays, onEventClick)
                } else {
                    val (allDay, notAllDay) = weekInstances.partition { it.allDay }
                    val allDayByDate = weekDays.associateWith { d -> allDay.filter { d in it.spanDays } }
                    val timedByDateHour = weekDays.associateWith { d ->
                        notAllDay.filter { d in it.spanDays }.groupBy { it.startDateTime.hour }
                    }

                    AllDayRow(allDayByDate, vEventsByID, calendars, weekDays, onEventClick)
                    Spacer(Modifier.onGloballyPositioned {
                        if (page == pagerState.currentPage) {
                            yOffset = with(density) { it.positionInParent().y.toDp() }
                        }
                    })
                    HourlyGrid(
                        timedByDateHour,
                        weekDays,
                        verticalState,
                        onEventClick,
                        PaddingValues(0.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SummaryGrid(
    context: android.content.Context,
    instances: List<Instance>,
    vEventsByID: Map<Long, Event>,
    calendars: Map<Long, Calendar>,
    weekDays: List<LocalDate>,
    onEventClick: (Instance) -> Unit
) {
    Row(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
        weekDays.forEachIndexed { index, day ->
            val dayInstances = instances.filter { day in it.spanDays }.sortedBy { it.startDateTime }
            val isToday = day == today
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                    .padding(2.dp)
            ) {
                dayInstances.forEach { instance ->
                    val ev = vEventsByID[instance.eventID]!!
                    SummaryEventItem(context, instance, ev, calendars, onEventClick)
                }
            }
            if (index < weekDays.size - 1) {
                VerticalDivider(modifier = Modifier.fillMaxHeight())
            }
        }
    }
}

@Composable
fun SummaryEventItem(
    context: android.content.Context,
    instance: Instance,
    ev: Event,
    calendars: Map<Long, Calendar>,
    onEventClick: (Instance) -> Unit
) {
    Box(
        Modifier
            .padding(bottom = 2.dp)
            .background(Color(ev.color ?: calendars[ev.calendarID]!!.color), RoundedCornerShape(4.dp))
            .fillMaxWidth()
            .clickable { onEventClick(instance) }
            .padding(4.dp)
    ) {
        Column {
            Text(
                ev.title.ifEmpty { context.getString(R.string.no_title) },
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp
            )
            if (!instance.allDay) {
                val timeFmt = if(DateFormat.is24HourFormat(context)) timeFormat24 else timeFormat12
                Text(
                    "${instance.startDateTime.time.format(timeFmt)} - ${instance.endDateTime.time.format(timeFmt)}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    lineHeight = 10.sp
                )
            }
        }
    }
}

@Composable
fun MonthView(
    context: android.content.Context,
    events: List<Event>,
    calendars: Map<Long, Calendar>,
    calendarVisibility: Map<Long, Boolean>,
    anchorDate: LocalDate,
    dateViewing: LocalDate,
    loadInstances: suspend (Instant, Instant) -> List<Instance>,
    onEventClick: (Instance) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onDateViewingChanged: (LocalDate) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 5000) { 10000 }
    var programmaticScroll by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        if (programmaticScroll) return@LaunchedEffect
        val monthDate = anchorDate.plus(DatePeriod(months = pagerState.currentPage - 5000))
        onDateViewingChanged(monthDate)
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
        val monthDate = anchorDate.plus(DatePeriod(months = page - 5000))
        val firstOfMonth = LocalDate(monthDate.year, monthDate.month, 1)
        val lastOfMonth = firstOfMonth.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        
        val locale = context.resources.configuration.locales[0]
        val firstDayOfWeek = getFirstDayOfWeekValue(locale)
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

        val vEventsByID = remember(events) { events.associateBy { it.id!! } }
        val monthInstances by produceState(emptyList<Instance>(), events, calendarVisibility, startDay, endDay) {
            value = loadInstances(
                startDay.atStartOfDayIn(TimeZone.currentSystemDefault()),
                endDay.atEndOfDayIn(TimeZone.currentSystemDefault())
            )
        }

        Column(Modifier.fillMaxSize()) {
            weeks.forEach { weekSunday ->
                MonthWeekRow(
                    Modifier.weight(1f),
                    weekSunday,
                    vEventsByID,
                    calendars,
                    onEventClick,
                    onDayClick,
                    context,
                    monthDate.month.number,
                    monthInstances
                )
            }
        }
    }
}

@Composable
fun MonthWeekRow(
    modifier: Modifier,
    weekSunday: LocalDate,
    vEventsByID: Map<Long, Event>,
    calendars: Map<Long, Calendar>,
    onEventClick: (Instance) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    context: android.content.Context,
    viewingMonth: Int,
    allInstances: List<Instance>
) {
    val weekDays = (0..6).map { weekSunday.plus(DatePeriod(days = it)) }

    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        weekDays.forEach { date ->
            val dayInstances = allInstances.filter { date in it.spanDays }
                .sortedBy { it.startDateTime }
            val isToday = date == Clock.System.todayIn(TimeZone.currentSystemDefault())
            val isPartOfViewingMonth = date.month.number == viewingMonth
            
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                    .clickable { onDayClick(date) }
                    .padding(2.dp)
            ) {
                Text(
                    text = date.day.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary else if (isPartOfViewingMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isToday || isPartOfViewingMonth) FontWeight.Bold else FontWeight.Normal
                )
                dayInstances.forEach { instance ->
                    val ev = vEventsByID[instance.eventID]!!
                    SummaryEventItem(context, instance, ev, calendars, onEventClick)
                }
            }
        }
    }
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
    val initialIndex = 50000
    val listState = rememberLazyListState(initialIndex)
    val vEventsByID = remember(events) { events.associateBy { it.id!! } }

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
        items(100000) { index ->
            val date = anchorDate.plus(DatePeriod(days = index - initialIndex))
            val dayInstances by produceState(emptyList<Instance>(), date, events, calendarVisibility) {
                value = loadInstances(
                    date.atStartOfDayIn(TimeZone.currentSystemDefault()),
                    date.plus(DatePeriod(days = 1)).atStartOfDayIn(TimeZone.currentSystemDefault())
                ).sortedBy { it.startDateTime }
            }

            val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
            val isToday = date == today

            Column(Modifier.fillMaxWidth().then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)) else Modifier)) {
                Text(
                    text = date.format(dateFormat),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                dayInstances.forEach { instance ->
                    val ev = vEventsByID[instance.eventID]!!
                    ListItem(
                        content = { Text(ev.title.ifEmpty { context.getString(R.string.no_title) }) },
                        supportingContent = {
                            Text(dateRangeString(context, instance.startDateTimeDisplay.date, instance.endDateTimeDisplay.date, instance.startDateTimeDisplay.time, instance.endDateTimeDisplay.time, instance.allDay, includeDate = false))
                        },
                        leadingContent = {
                            Box(Modifier.size(16.dp).background(Color(ev.color ?: calendars[ev.calendarID]!!.color), CircleShape))
                        },
                        modifier = Modifier.clickable { onEventClick(instance) }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

fun LocalDate.atEndOfDayIn(currentSystemDefault: TimeZone): Instant {
    return this.plus(DatePeriod(days = 1)).atStartOfDayIn(currentSystemDefault)
}

@Composable
private fun WeekHeader(weekDays: List<LocalDate>) {
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    Row(modifier = Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
        weekDays.forEach { d ->
            val isToday = d == today
            Column(
                Modifier
                    .weight(1f)
                    .then(
                        if (isToday) Modifier.background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                        ) else Modifier
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    d.dayOfWeek.name.take(3),
                    Modifier,
                    if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    d.day.toString(),
                    fontWeight = FontWeight.Bold,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun AllDayRow(
    allDayByDate: Map<LocalDate, List<Instance>>,
    events: Map<Long, Event>,
    calendars: Map<Long, Calendar>,
    weekDays: List<LocalDate>,
    onEventClick: (Instance) -> Unit
) {
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
        weekDays.forEach { d ->
            val instances = allDayByDate[d].orEmpty()
            Column(Modifier.weight(1f)) {
                if (instances.isEmpty()) {
                    Box(modifier = Modifier
                        .height(32.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
                } else {
                    Column {
                        instances.forEach { instance ->
                            val ev = events[instance.eventID]!!
                            Box(
                                Modifier
                                    .padding(bottom = 4.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline)
                                    .background(Color(ev.color ?: calendars[ev.calendarID]!!.color))
                                    .height(28.dp)
                                    .clickable { onEventClick(instance) }
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    ev.title.ifEmpty { stringResource(R.string.no_title) },
                                    Modifier.padding(4.dp),
                                    Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HourlyGrid(
    timedByDateHour: Map<LocalDate, Map<Int, List<Instance>>>,
    weekDays: List<LocalDate>,
    verticalState: ScrollState,
    onEventClick: (Instance) -> Unit,
    innerPadding: PaddingValues
) {
    // Each hour row height
    val hourRowHeight = 56.dp
    val minEventHeight = 18.dp
    val minEventWidth = 56.dp

    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    var now by remember { mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())) }

    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            kotlinx.coroutines.delay(60.seconds)
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .verticalScroll(verticalState)
            .padding(bottom = innerPadding.calculateBottomPadding()).padding(bottom = 4.dp), Arrangement.spacedBy(4.dp)
    ) {
        // create 7 equal columns with weight so all 7 fit on screen
        for (d in weekDays) {
            val isToday = d == today
            // collect unique timed events for this day (timedByDateHour groups by hour)
            val eventsForDay = timedByDateHour[d]?.values?.flatten().orEmpty().distinctBy { it.id }

            Box(Modifier.weight(1f).background(if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f) else Color.Transparent)) {
                // background hourly grid — fixed 24 rows
                Column {
                    for (hour in 0..23) {
                        Box(
                            Modifier
                                .height(hourRowHeight)
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }

                if (isToday) {
                    val minutesPassed = now.hour * 60 + now.minute
                    val yOffset = hourRowHeight * (minutesPassed.toFloat() / 60f)
                    Box(
                        Modifier
                            .offset(y = yOffset - 1.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Color.Red)
                            .zIndex(10f)
                    )
                }

                // compute positioned events using the helper that assigns columns for overlaps
                val positioned = computePositionedEventsForDay(eventsForDay, d)
                val instancesById = remember(eventsForDay) { eventsForDay.associateBy { it.id } }

                // overlay event segments positioned by their time within the day and column
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columnWidth = this.maxWidth

                    positioned.forEach { ev ->
                        val instance = instancesById.getValue(ev.instanceID)
                        // compute vertical position and height
                        val startHours = ev.startMinutes.toFloat() / 60f
                        val lengthHours = (ev.endMinutes - ev.startMinutes).toFloat() / 60f

                        val yOffset = hourRowHeight * startHours
                        var heightDp = hourRowHeight * lengthHours
                        if (heightDp < minEventHeight) heightDp = minEventHeight

                        // compute horizontal position and size
                        val widthFraction = 1f / ev.totalColumns.toFloat()
                        val xFraction = ev.columnIndex * widthFraction
                        val xOffsetDp = columnWidth * xFraction
                        val widthDp = (columnWidth * widthFraction).coerceAtLeast(minEventWidth)

                        Box(
                            Modifier
                                .offset(xOffsetDp, yOffset)
                                .size(widthDp, heightDp)
                                .padding(2.dp)
                                .zIndex(1f + ev.columnIndex * 0.01f)
                                .border(1.dp, MaterialTheme.colorScheme.outline)
                                .background(Color(ev.color))
                                .clickable { onEventClick(instance) }
                        ) {
                            Text(
                                ev.title.ifEmpty { stringResource(R.string.no_title) },
                                Modifier.padding(6.dp),
                                Color.White,
                                maxLines = 2,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
