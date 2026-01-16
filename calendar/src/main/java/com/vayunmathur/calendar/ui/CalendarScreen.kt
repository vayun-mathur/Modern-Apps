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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.Calendar
import com.vayunmathur.calendar.ContactViewModel
import com.vayunmathur.calendar.Event
import com.vayunmathur.calendar.Instance
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.ui.IconAdd
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
    val initialDate = lastViewed ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    var dateViewing by remember { mutableStateOf(initialDate) }

    // state for which week to show; 0 = current week, +1 = next week, -1 = previous week

    // shared vertical scroll so hour labels and grid scroll together
    val verticalState = rememberScrollState()

    ResultEffect<LocalDate>("GotoDate") { result ->
        dateViewing = result
    }
    val visibleSunday = dateViewing - DatePeriod(days = dateViewing.dayOfWeek.isoDayNumber % 7)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // show month/year of the currently visible week's Sunday
                    val mon = MonthNames.ENGLISH_ABBREVIATED.names[visibleSunday.month.number - 1]
                    Row(Modifier.clickable { backStack.add(Route.Calendar.GotoDialog(dateViewing)) }, verticalAlignment = Alignment.CenterVertically) {
                        Text("$mon ${visibleSunday.year}", fontWeight = FontWeight.Bold)
                        Icon(painterResource(R.drawable.arrow_drop_down_24px), null)
                    }
                },
                actions = {
                    IconButton(onClick = { backStack.add(Route.Settings) }) {
                        Icon(painterResource(R.drawable.settings_24px), contentDescription = "Settings")
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(),
        floatingActionButton = {
            FloatingActionButton(onClick = {
                // persist currently viewed date before navigating to the new event page
                viewModel.setLastViewedDate(dateViewing)
                backStack.add(Route.EditEvent(null))
            }) {
                IconAdd()
            }
        },
    ) { innerPadding ->
        Row(Modifier.padding(innerPadding).fillMaxSize()) {

            var yOffset by remember { mutableStateOf(0.dp) }

            // Hour labels column - fixed on the left, shares vertical scroll state with grid
            Column() {
                Spacer(Modifier.height(yOffset))
                Column(Modifier.verticalScroll(verticalState)) {
                    for (hour in 0..23) {
                        Box(modifier = Modifier.height(56.dp).width(56.dp)) {
                            Text(
                                text = if (hour == 0) "12 AM" else if (hour < 12) "$hour AM" else if (hour == 12) "12 PM" else "${hour - 12} PM",
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
            // remember drag total so it survives recomposition
            val dragTotal = remember { mutableStateOf(0f) }

            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, delta ->
                        dragTotal.value += delta
                        change.consume()
                    },
                    onDragEnd = {
                        val threshold = 100f // pixels
                        if (dragTotal.value <= -threshold) {
                            dateViewing += DatePeriod(days = 7)
                        } else if (dragTotal.value >= threshold) {
                            dateViewing -= DatePeriod(days = 7)
                        }
                        dragTotal.value = 0f
                    },
                    onDragCancel = {
                        dragTotal.value = 0f
                    }
                )
            }
            ) {

                // animate between weekOffset values with a horizontal slide
                AnimatedContent(targetState = visibleSunday, transitionSpec = {
                    val duration = 300
                    if (targetState > initialState) {
                        slideInHorizontally(animationSpec = tween(durationMillis = duration)) { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally(animationSpec = tween(durationMillis = duration)) { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally(animationSpec = tween(durationMillis = duration)) { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally(animationSpec = tween(durationMillis = duration)) { width -> width } + fadeOut()
                    }
                }) { sunday ->
                    val weekDays = (0..6).map { sunday.plus(DatePeriod(days = it)) }

                    val weekInstances = Instance.getInstances(context, weekDays.first().atStartOfDayIn(TimeZone.currentSystemDefault()), weekDays.last().atEndOfDayIn(TimeZone.currentSystemDefault()))
                        .filter { it.eventID in vEventsByID }.filter { calendarVisibility[vEventsByID[it.eventID]!!.calendarID] ?: true }

                    val (allDay, notAllDay) = weekInstances.partition { it.allDay }

                    val allDayByDate: Map<LocalDate, List<Instance>> = weekDays.associateWith { d ->
                        allDay.filter { instance -> d in instance.spanDays }
                    }

                    val timedByDateHour: Map<LocalDate, Map<Int, List<Instance>>> = weekDays.associateWith { d ->
                        val timed = notAllDay.filter { instance -> d in instance.spanDays }
                        timed.groupBy { ev ->
                            ev.startDateTime.hour
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        WeekHeader(weekDays)
                        AllDayRow(allDayByDate, vEventsByID, calendars, weekDays) { instance ->
                            // persist currently viewed date so returning restores the same week
                            viewModel.setLastViewedDate(dateViewing)
                            backStack.add(Route.Event(instance))
                        }
                        Spacer(Modifier.onGloballyPositioned{
                            yOffset = with(density) { it.positionInParent().y.toDp() }
                        })
                        HourlyGrid(timedByDateHour, weekDays, verticalState, onEventClick = { instance ->
                            viewModel.setLastViewedDate(dateViewing)
                            backStack.add(Route.Event(instance))
                        })
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        weekDays.forEach { d ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(text = d.dayOfWeek.name.take(3), fontSize = 12.sp, color = Color.Gray)
                Text(text = d.day.toString(), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AllDayRow(allDayByDate: Map<LocalDate, List<Instance>>,
                      events: Map<Long, Event>, calendars: Map<Long, Calendar>, weekDays: List<LocalDate>, onEventClick: (Instance) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        weekDays.forEach { d ->
            val instances = allDayByDate[d].orEmpty()
            Column(modifier = Modifier.weight(1f)) {
                if (instances.isEmpty()) {
                    Box(modifier = Modifier.height(32.dp).border(1.dp, Color.DarkGray)) {}
                } else {
                    Column {
                        instances.forEach { instance ->
                            val ev = events[instance.eventID]!!
                            Box(modifier = Modifier
                                .padding(bottom = 4.dp)
                                .border(1.dp, Color.Black)
                                .background(Color(ev.color ?: calendars[ev.calendarID]!!.color))
                                .height(28.dp)
                                .clickable {
                                    onEventClick(instance)
                                }
                                .fillMaxWidth()) {
                                Text(text = ev.title.ifEmpty { "(No title)" }, color = Color.White, modifier = Modifier.padding(4.dp), fontSize = 12.sp)
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
    onEventClick: (Instance) -> Unit
) {
    // Each hour row height
    val hourRowHeight = 56.dp
    val minEventHeight = 18.dp
    val minEventWidth = 56.dp

    Column(modifier = Modifier.fillMaxSize().verticalScroll(verticalState)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // create 7 equal columns with weight so all 7 fit on screen
            for (d in weekDays) {
                // collect unique timed events for this day (timedByDateHour groups by hour)
                val eventsForDay = timedByDateHour[d]?.values?.flatten().orEmpty().distinctBy { it.id }

                Box(modifier = Modifier.weight(1f)) {
                    // background hourly grid — fixed 24 rows
                    Column {
                        for (hour in 0..23) {
                            Box(
                                modifier = Modifier
                                    .height(hourRowHeight)
                                    .fillMaxWidth()
                                    .border(1.dp, Color(0xFF222222))
                                    .background(Color(0xFF0F0F0F))
                            ) {
                                // hour cell background only; we overlay events below
                            }
                        }
                    }

                    // compute positioned events using the helper that assigns columns for overlaps
                    val positioned = computePositionedEventsForDay(eventsForDay, d)

                    // overlay event segments positioned by their time within the day and column
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val columnWidth = this.maxWidth
                        val hourHeight = hourRowHeight

                        positioned.forEach { ev ->
                            val instance = eventsForDay.find { it.id == ev.instanceID }!!
                            // compute vertical position and height
                            val startHours = ev.startMinutes.toFloat() / 60f
                            val lengthHours = (ev.endMinutes - ev.startMinutes).toFloat() / 60f

                            val yOffset = hourHeight * startHours
                            var heightDp = hourHeight * lengthHours
                            if (heightDp < minEventHeight) heightDp = minEventHeight

                            // compute horizontal position and size
                            val widthFraction = 1f / ev.totalColumns.toFloat()
                            val xFraction = ev.columnIndex * widthFraction
                            val xOffsetDp = columnWidth * xFraction
                            val widthDp = (columnWidth * widthFraction).coerceAtLeast(minEventWidth)

                            Box(
                                modifier = Modifier
                                    .offset(x = xOffsetDp, y = yOffset)
                                    .width(widthDp)
                                    .height(heightDp)
                                    .padding(2.dp)
                                    .zIndex(1f + ev.columnIndex * 0.01f)
                                    .border(1.dp, Color.Black)
                                    .background(Color(ev.color))
                                    .clickable(enabled = true) { onEventClick(instance) }
                            ) {
                                Text(
                                    text = ev.title.ifEmpty { "(No title)" },
                                    color = Color.White,
                                    modifier = Modifier.padding(6.dp),
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
}
