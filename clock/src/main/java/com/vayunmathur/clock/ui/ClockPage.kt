package com.vayunmathur.clock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.clock.MAIN_PAGES
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.citiesToTimezones
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.nowState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockPage(backStack: NavBackStack<Route>, ds: DataStoreUtils) {
    val now by nowState()
    val time = now.toLocalDateTime(TimeZone.currentSystemDefault())
    val timeZones by ds.stringSetFlow("time_zones").collectAsState(setOf())
    Scaffold(topBar = {
        TopAppBar({Text("Clock")})
    }, bottomBar = {
        BottomNavBar(backStack, MAIN_PAGES, Route.Clock)
    }, floatingActionButton = {
        FloatingActionButton({
            backStack.add(Route.SelectTimeZonesDialog)
        }) { IconAdd() }
    }) { paddingValues ->
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = paddingValues, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(time.time.format(LocalTime.Format {
                        amPmHour(Padding.NONE)
                        chars(":")
                        minute()
                        chars(":")
                        second()
                    }), style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.width(8.dp))
                    Text(if(time.time.hour >= 12) "PM" else "AM", style = MaterialTheme.typography.displayMedium)
                }
            }
            item {
                Text(time.date.format(LocalDate.Format {
                    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
                    chars(", ")
                    monthName(MonthNames.ENGLISH_ABBREVIATED)
                    chars(" ")
                    day(Padding.NONE)
                }))
            }
            items(timeZones.toList()) {city ->
                val it = citiesToTimezones?.get(city) ?: return@items
                val timeHere = now.toLocalDateTime(TimeZone.of(it))
                Card {
                    ListItem({Text(city)}, trailingContent = {
                        Text(timeHere.time.format(LocalTime.Format {
                            amPmHour(Padding.NONE)
                            chars(":")
                            minute()
                        }) + if(timeHere.time.hour >= 12) "PM" else "AM")
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
    }
}