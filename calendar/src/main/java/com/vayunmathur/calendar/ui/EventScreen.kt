package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.ContactViewModel
import com.vayunmathur.calendar.Instance
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.pop
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(viewModel: ContactViewModel, instance: Instance, backStack: NavBackStack<Route>) {
    val events by viewModel.events.collectAsState()
    val calendars by viewModel.calendars.collectAsState()

    val event = events.find { it.id == instance.eventID }
    if (event == null) {
        // simple empty state
        Text("Event not found")
        return
    }

    val calendar = calendars.find { it.id == event.calendarID }!!

    val isEditable = calendar.canModify

    Scaffold(topBar = {
        TopAppBar({}, navigationIcon = {
            IconNavigation(backStack)
        }, actions = {
            if(isEditable) {
                IconButton({
                    backStack.add(Route.EditEvent(event.id))
                }) {
                    IconEdit()
                }
                IconButton({
                    viewModel.deleteEvent(event.id!!)
                    backStack.pop()
                }) {
                    IconDelete()
                }
            }
        })
    }, contentWindowInsets = WindowInsets()) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            ListItem({
                Text(event.title, style = MaterialTheme.typography.titleLarge)
            }, supportingContent = {
                Column {
                    Text(calendar.displayName)
                    Text(dateRangeString(instance.startDateTimeDisplay.date, instance.endDateTimeDisplay.date, instance.startDateTimeDisplay.time, instance.endDateTimeDisplay.time, instance.allDay))
                    instance.rrule?.let { Text(it.toString()) }
                }
            }, leadingContent = {
                Box(Modifier.size(24.dp).background(Color(calendar.color), RoundedCornerShape(4.dp)))
            })
            if(event.description.isNotBlank()) ListItem({
                Text(event.description)
            }, leadingContent = {
                Icon(painterResource(R.drawable.description_24px), null)
            })
            if(event.location.isNotBlank()) ListItem({Text(event.location)}, leadingContent =
                {Icon(painterResource(R.drawable.globe_24px), null)},
            )
        }
    }
}

fun dateRangeString(startDate: LocalDate, endDate: LocalDate, startTime: LocalTime, endTime: LocalTime, allDay: Boolean): String {
    return if(allDay) {
        if(startDate.toEpochDays() + 1 == endDate.toEpochDays()) {
            startDate.format(dateFormat)
        } else {
            "${startDate.format(dateFormat)} - ${endDate.format(dateFormat)}"
        }
    } else {
        if(startDate == endDate) {
            "${startDate.format(dateFormat)} • ${startTime.format(timeFormat)} - ${endTime.format(timeFormat)}"
        } else {
            "${startDate.format(dateFormat)}, ${startTime.format(timeFormat)} - ${endDate.format(dateFormat)}, ${endTime.format(timeFormat)}"
        }
    }
}