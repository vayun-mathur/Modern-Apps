package com.vayunmathur.calendar.ui

import com.vayunmathur.library.util.DateNameStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.platform.CalendarActions
import com.vayunmathur.calendar.platform.CalendarUiState
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconToday
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import com.vayunmathur.library.util.localizedMonthNames
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Binds [CalendarViewModel] to the stateless [CalendarScreen]. */
@Composable
fun CalendarScreen(viewModel: CalendarViewModel, backStack: NavBackStack<Route>) {
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
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    ResultEffect<LocalDate>("GotoDate") { result ->
        viewModel.setSelectedDate(result)
    }

    CalendarScreen(
        state = CalendarUiState(
            layout = currentLayout,
            dateViewing = dateViewing,
            today = today,
            events = events,
            calendars = calendars,
            calendarVisibility = calendarVisibility,
        ),
        // Navigation is the binder's job; everything else comes from the ViewModel. Not
        // remembered, so `dateViewing` captured below is always the current one.
        actions = object : CalendarActions by viewModel {
            override fun openDatePicker(date: LocalDate) {
                backStack.add(Route.Calendar.GotoDialog(date))
            }

            override fun openSettings() {
                backStack.add(Route.Settings)
            }

            override fun openEvent(instance: Instance) {
                viewModel.setLastViewedDate(dateViewing)
                // On a two-pane window the detail stays put: swap it in place so Back
                // still returns to the calendar rather than to the previous event.
                if (backStack.last() is Route.Event || backStack.last() is Route.EditEvent) {
                    backStack.setLast(Route.Event(instance))
                } else {
                    backStack.add(Route.Event(instance))
                }
            }

            override fun createEvent() {
                // persist currently viewed date before navigating to the new event page
                viewModel.setLastViewedDate(dateViewing)
                if (backStack.last() is Route.Event || backStack.last() is Route.EditEvent) {
                    backStack.setLast(Route.EditEvent(null))
                } else {
                    backStack.add(Route.EditEvent(null))
                }
            }

            override fun createEventOn(date: LocalDate) {
                viewModel.setLastViewedDate(dateViewing)
                // Pre-fill the pressed date at the current hour so the editor opens on a sensible
                // timed slot; the editor derives a default end half an hour later.
                val tz = TimeZone.currentSystemDefault()
                val nowTime = Clock.System.now().toLocalDateTime(tz).time
                val begin = date.atTime(nowTime.hour, 0).toInstant(tz).toEpochMilliseconds()
                if (backStack.last() is Route.Event || backStack.last() is Route.EditEvent) {
                    backStack.setLast(Route.EditEvent(null, beginTime = begin))
                } else {
                    backStack.add(Route.EditEvent(null, beginTime = begin))
                }
            }
        },
    )
}

/**
 * The calendar screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(state: CalendarUiState, actions: CalendarActions) {
    val context = LocalContext.current

    AppScaffold(
        title = {
            // show month/year of the currently visible date
            val mon = localizedMonthNames(DateNameStyle.SHORT)[state.dateViewing.month.number - 1]
            Row(
                Modifier.clickable { actions.openDatePicker(state.dateViewing) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.month_year_format, mon, state.dateViewing.year), fontWeight = FontWeight.Bold)
                IconArrowDropDown()
            }
        },
        actions = {
            var showLayoutMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { showLayoutMenu = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(state.layout.shortNameRes), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        IconArrowDropDown(tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = showLayoutMenu, onDismissRequest = { showLayoutMenu = false }) {
                    CalendarViewModel.CalendarLayout.entries.forEach { layout ->
                        DropdownMenuItem(
                            text = { Text(stringResource(layout.prettyNameRes)) },
                            onClick = {
                                actions.setLayout(layout)
                                showLayoutMenu = false
                            }
                        )
                    }
                }
            }

            IconButton({ actions.setSelectedDate(state.today) }) {
                IconToday()
            }

            IconButton({ actions.openSettings() }) {
                IconSettings()
            }
        },
        floatingActionButton = {
            FloatingActionButton({ actions.createEvent() }) {
                IconAdd()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            when (state.layout) {
                CalendarViewModel.CalendarLayout.Agenda -> AgendaView(
                    context, state.events, state.calendars, state.calendarVisibility, state.today, state.dateViewing,
                    actions::visibleInstances,
                    onEventClick = { actions.openEvent(it) },
                    onDateViewingChanged = { actions.setSelectedDate(it) }
                )
                CalendarViewModel.CalendarLayout.Month -> MonthCalendarView(
                    context, state.events, state.calendars, state.calendarVisibility, state.today, state.dateViewing,
                    actions::visibleInstances,
                    onEventClick = { actions.openEvent(it) },
                    onDayClick = { actions.setSelectedDate(it) },
                    onDayLongClick = { actions.createEventOn(it) },
                    onDateViewingChanged = { actions.setSelectedDate(it) },
                    previewInstances = state.previewInstances,
                )
                CalendarViewModel.CalendarLayout.MonthAgenda -> MonthAgendaView(
                    context, state.events, state.calendars, state.calendarVisibility, state.today, state.dateViewing,
                    actions::visibleInstances,
                    onEventClick = { actions.openEvent(it) },
                    onDayClick = { actions.setSelectedDate(it) },
                    onDayLongClick = { actions.createEventOn(it) },
                    onDateViewingChanged = { actions.setSelectedDate(it) },
                    previewInstances = state.previewInstances,
                )
                else -> {
                    CalendarPagerView(
                        context,
                        state.layout,
                        state.today,
                        state.dateViewing,
                        state.events,
                        state.calendars,
                        state.calendarVisibility,
                        actions::visibleInstances,
                        onEventClick = { actions.openEvent(it) },
                        onDateViewingChanged = { actions.setSelectedDate(it) }
                    )
                }
            }
        }
    }
}
