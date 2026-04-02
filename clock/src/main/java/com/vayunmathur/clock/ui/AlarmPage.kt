package com.vayunmathur.clock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.clock.AlarmScheduler
import com.vayunmathur.clock.MAIN_PAGES
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val alarms by viewModel.data<Alarm>().collectAsState()
    val context = LocalContext.current
    val alarmScheduler = remember { AlarmScheduler.get() }
    ResultEffect<LocalTime>("alarm_time") {
        val newAlarm = Alarm(it, "", true, 0)
        val id = viewModel.upsert(newAlarm)
        alarmScheduler.schedule(context,newAlarm.copy(id = id))
    }
    Scaffold(topBar = {
        TopAppBar({Text("Alarm")})
    }, bottomBar = {
        BottomNavBar(backStack, MAIN_PAGES, Route.Alarm)
    }, floatingActionButton = {
        FloatingActionButton({
            backStack.add(Route.NewAlarmDialog)
        }) {
            IconAdd()
        }
    }) { paddingValues ->
        LazyColumn(contentPadding = paddingValues) {
            items(alarms) { alarm ->
                AlarmCard(backStack, alarm, viewModel, alarmScheduler)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlarmCard(
    backStack: NavBackStack<Route>,
    alarm: Alarm,
    viewModel: DatabaseViewModel,
    alarmScheduler: AlarmScheduler
) {
    val context = LocalContext.current
    ResultEffect<LocalTime>("alarm_set_time_${alarm.id}") {
        val newAlarm = alarm.copy(time = it)
        if(newAlarm.enabled) {
            alarmScheduler.schedule(context, newAlarm)
        }
        viewModel.upsert(newAlarm)
    }
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    alarm.time.format(LocalTime.Format {
                        amPmHour(Padding.NONE)
                        char(':')
                        minute()
                        amPmMarker(" AM", " PM")
                    }),
                    Modifier.clickable{ backStack.add(Route.AlarmSetTimeDialog(alarm.id, alarm.time))},
                    style = MaterialTheme.typography.displayMedium
                )
                Row {
                    Switch(checked = alarm.enabled, onCheckedChange = {
                        val newAlarm = alarm.copy(enabled = it)
                        if(newAlarm.enabled) {
                            alarmScheduler.schedule(context, newAlarm)
                        } else {
                            alarmScheduler.cancel(context, newAlarm)
                        }
                        viewModel.upsertAsync(alarm.copy(enabled = it))
                    })
                    IconButton({
                        alarmScheduler.cancel(context, alarm)
                        viewModel.delete(alarm)
                    }) {
                        IconDelete()
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                "SMTWTFS".forEachIndexed { idx, day ->
                    ToggleButton(
                        checked = alarm.days and (1 shl idx) != 0,
                        onCheckedChange = {
                            val newDays = if (alarm.days and (1 shl idx) != 0) alarm.days and (1 shl idx).inv() else alarm.days or (1 shl idx)
                            val newAlarm = alarm.copy(days = newDays)
                            if(newAlarm.enabled) {
                                alarmScheduler.schedule(context, newAlarm)
                            }
                            viewModel.upsertAsync(newAlarm)
                        }
                    ) {
                        Text(day.toString())
                    }
                }
            }
        }
    }
}