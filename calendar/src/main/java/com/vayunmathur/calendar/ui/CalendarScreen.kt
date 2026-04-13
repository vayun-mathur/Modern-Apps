package com.vayunmathur.calendar.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.util.ContactViewModel
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconSettings
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
import kotlin.time.Clock
import kotlin.time.Instant


@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun CalendarScreen(viewModel: ContactViewModel, backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    val events by viewModel.events.collectAsState()
    val calendarsList by viewModel.calendars.collectAsState()
    val calendars = calendarsList.associateBy { it.id }
    val calendarVisibility by viewModel.calendarVisibility.collectAsState()

    val vEventsByID = events.associateBy { it.id!! }


    // compute today's date and restore last viewed date if available
    val lastViewed by viewModel.lastViewedDate.collectAsState()
    val initialDate =
        lastViewed ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    var dateViewing by remember { mutableStateOf(initialDate) }

    // state for which week to show; 0 = current week, +1 = next week, -1 = previous week

    // shared vertical scroll so hour labels and grid scroll together
    val verticalState = rememberScrollState()

    ResultEffect<LocalDate>("GotoDate") { result ->
        dateViewing = result
    }
    val visibleSunday = dateViewing - DatePeriod(days = dateViewing.dayOfWeek.isoDayNumber % 7)

    Scaffold(
        Modifier,
        {
            TopAppBar(
                {
                    // show month/year of the currently visible week's Sunday
                    val mon = MonthNames.ENGLISH_ABBREVIATED.names[visibleSunday.month.number - 1]
                    Row(
                        Modifier.clickable { backStack.add(Route.Calendar.GotoDialog(dateViewing)) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$mon ${visibleSunday.year}", fontWeight = FontWeight.Bold)
                        Icon(painterResource(R.drawable.arrow_drop_down_24px), null)
                    }
                }, actions = {
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
        Row(
            Modifier
                .padding(
                    innerPadding.calculateLeftPadding(LocalLayoutDirection.current),
                    innerPadding.calculateTopPadding(),
                    innerPadding.calculateRightPadding(LocalLayoutDirection.current)
                )
                .fillMaxSize()
        ) {
            var yOffset by remember { mutableStateOf(0.dp) }

            // Hour labels column - fixed on the left, shares vertical scroll state with grid
            Column {
                Spacer(Modifier.height(yOffset))
                Column(
                    Modifier
                        .verticalScroll(verticalState)
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    for (hour in 0..23) {
                        Box(modifier = Modifier
                            .height(56.dp)
                            .width(56.dp)) {
                            Text(
                                text = if (hour == 0) stringResource(R.string.twelve_am) else if (hour < 12) stringResource(R.string.hour_am, hour) else if (hour == 12) stringResource(R.string.twelve_pm) else stringResource(R.string.hour_pm, hour - 12),
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
            // remember drag total so it survives recomposition
            var dragTotal by remember { mutableFloatStateOf(0f) }

            Box(Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures({}, {
                        val threshold = 100f // pixels
                        if (dragTotal <= -threshold) {
                            dateViewing += DatePeriod(days = 7)
                        } else if (dragTotal >= threshold) {
                            dateViewing -= DatePeriod(days = 7)
                        }
                        dragTotal = 0f
                    }, { dragTotal = 0f }, { change, delta ->
                        dragTotal += delta
                        change.consume()
                    })
                }) {

                // animate between weekOffset values with a horizontal slide
                AnimatedContent(visibleSunday, Modifier, {
                    val duration = 300
                    if (targetState > initialState) {
                        slideInHorizontally(tween(duration)) { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally(tween(duration)) { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally(tween(duration)) { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally(tween(duration)) { width -> width } + fadeOut()
                    }
                }) { sunday ->
                    val weekDays = (0..6).map { sunday.plus(DatePeriod(days = it)) }

                    val weekInstances = Instance.getInstances(
                        context,
                        weekDays.first().atStartOfDayIn(TimeZone.currentSystemDefault()),
                        weekDays.last().atEndOfDayIn(TimeZone.currentSystemDefault())
                    )
                        .filter { it.eventID in vEventsByID }
                        .filter { calendarVisibility[vEventsByID[it.eventID]!!.calendarID] ?: true }

                    val (allDay, notAllDay) = weekInstances.partition { it.allDay }

                    val allDayByDate: Map<LocalDate, List<Instance>> = weekDays.associateWith { d ->
                        allDay.filter { instance -> d in instance.spanDays }
                    }

                    val timedByDateHour: Map<LocalDate, Map<Int, List<Instance>>> =
                        weekDays.associateWith { d ->
                            val timed = notAllDay.filter { instance -> d in instance.spanDays }
                            timed.groupBy { ev ->
                                ev.startDateTime.hour
                            }
                        }

                    Column(Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        WeekHeader(weekDays)
                        AllDayRow(allDayByDate, vEventsByID, calendars, weekDays) { instance ->
                            // persist currently viewed date so returning restores the same week
                            viewModel.setLastViewedDate(dateViewing)
                            backStack.add(Route.Event(instance))
                        }
                        Spacer(Modifier.onGloballyPositioned {
                            yOffset = with(density) { it.positionInParent().y.toDp() }
                        })
                        HourlyGrid(
                            timedByDateHour,
                            weekDays,
                            verticalState,
                            onEventClick = { instance ->
                                viewModel.setLastViewedDate(dateViewing)
                                backStack.add(Route.Event(instance))
                            },
                            innerPadding
                        )
                    }
                }
            }
        }
    }
}

fun LocalDate.atEndOfDayIn(currentSystemDefault: TimeZone): Instant {
    return this.plus(DatePeriod(days = 1)).atStartOfDayIn(currentSystemDefault)
}

@Composable
private fun WeekHeader(weekDays: List<LocalDate>) {
    Row(modifier = Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
        weekDays.forEach { d ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.dayOfWeek.name.take(3), Modifier, Color.Gray, fontSize = 12.sp)
                Text(d.day.toString(), fontWeight = FontWeight.Bold)
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
                        .border(1.dp, Color.DarkGray)) {}
                } else {
                    Column {
                        instances.forEach { instance ->
                            val ev = events[instance.eventID]!!
                            Box(
                                Modifier
                                    .padding(bottom = 4.dp)
                                    .border(1.dp, Color.Black)
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

    Row(
        Modifier
            .fillMaxSize()
            .verticalScroll(verticalState)
            .padding(bottom = innerPadding.calculateBottomPadding()).padding(bottom = 4.dp), Arrangement.spacedBy(4.dp)
    ) {
        // create 7 equal columns with weight so all 7 fit on screen
        for (d in weekDays) {
            // collect unique timed events for this day (timedByDateHour groups by hour)
            val eventsForDay = timedByDateHour[d]?.values?.flatten().orEmpty().distinctBy { it.id }

            Box(Modifier.weight(1f)) {
                // background hourly grid — fixed 24 rows
                Column {
                    for (hour in 0..23) {
                        Box(
                            Modifier
                                .height(hourRowHeight)
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFF222222))
                                .background(Color(0xFF0F0F0F))
                        )
                    }
                }

                // compute positioned events using the helper that assigns columns for overlaps
                val positioned = computePositionedEventsForDay(eventsForDay, d)

                // overlay event segments positioned by their time within the day and column
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columnWidth = this.maxWidth

                    positioned.forEach { ev ->
                        val instance = eventsForDay.find { it.id == ev.instanceID }!!
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
                                .border(1.dp, Color.Black)
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
