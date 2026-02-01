package com.vayunmathur.findfamily.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.library.util.DatabaseViewModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val users by viewModel.data<User>().collectAsState()
    val waypoints by viewModel.data<Waypoint>().collectAsState()
    val locationValues by viewModel.data<LocationValue>().collectAsState()
    val userPositions by remember { derivedStateOf {
        locationValues.groupBy { it.userid }.mapValues { it.value.maxBy { it.timestamp } }
    } }

    Scaffold { paddingValues ->
        Column(Modifier.padding(paddingValues)) {

            Box(Modifier.fillMaxWidth().weight(1f)) {
                MapView(backStack, viewModel, navEnabled = true)
            }

            Surface(Modifier.heightIn(max = 400.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp)) {
                    items(users.filter { it.deleteAt == null }) {
                        UserCard(backStack, it, userPositions[it.id], true)
                    }
                    if (users.any { it.deleteAt != null }) {
                        item {
                            Text("Temporary Links")
                        }
                    }
                    items(users.filter { it.deleteAt != null }) {
                        UserCard(backStack, it, userPositions[it.id], true)
                    }
                    item {
                        if (waypoints.isNotEmpty()) {
                            Text("Saved Places")
                        }
                    }
                    items(waypoints) {
                        WaypointCard(backStack, it, users)
                    }
                }
            }
        }
    }
}

@Composable
fun WaypointCard(backStack: NavBackStack<Route>, waypoint: Waypoint, users: List<User>) {
    val usersWithin = users.filter { it.locationName == waypoint.name }
    val usersString = usersWithin.joinToString { it.name } + when(usersWithin.size) {
        0 -> "nobody is currently here"
        1 -> " is currently here"
        else -> " are currently here"
    }
    Card(Modifier.clickable(onClick = {
        backStack.add(Route.WaypointPage(waypoint.id))
    })) {
        ListItem(
            headlineContent = { Text(waypoint.name, fontWeight = FontWeight.Bold) },
            supportingContent = { Text(usersString) }
        )
    }
}

@OptIn(ExperimentalTime::class)
@Composable
fun UserCard(backStack: NavBackStack<Route>, user: User, locationValue: LocationValue?, showSupportingContent: Boolean) {
    val lastUpdatedTime = locationValue?.let { timestring(it.timestamp) } ?: "Never"
    val speed = locationValue?.speed?.times(10)?.roundToInt()?.div(10F) ?: 0.0
    val sinceTime = user.lastLocationChangeTime.toLocalDateTime(TimeZone.currentSystemDefault())
    val timeSinceEntry = Clock.System.now() - user.lastLocationChangeTime
    val sinceString = if(user.locationName == "Unnamed Location")
        ""
    else if(timeSinceEntry < 60.seconds)
        "Since just now"
    else if(timeSinceEntry < 15.minutes)
        "Since ${timeSinceEntry.inWholeMinutes} minutes ago"
    else {
        val formattedTime = sinceTime.format(LocalDateTime.Format {
            amPmHour(Padding.NONE)
            chars(":")
            minute()
            chars(" ")
            amPmMarker("am", "pm")
        })
        val formattedDate = when(sinceTime.date.toEpochDays() - Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays()) {
            0L -> "today"
            1L -> "yesterday"
            else -> sinceTime.date.format(DateFormats.MONTH_DAY)
        }
        "Since $formattedTime $formattedDate"
    }
    val context = LocalContext.current
    Card(if(showSupportingContent) Modifier.clickable(onClick = {
        backStack.add(Route.UserPage(user.id))
    }) else Modifier) {
        ListItem(
            leadingContent = {
                if(user.deleteAt == null)
                    Column(Modifier.width(65.dp)) {
                        UserPicture(user, 65.dp)
                        Spacer(Modifier.height(4.dp))
                        locationValue?.battery?.let {
                            BatteryBar(it)
                        }
                    }
            },
            headlineContent = { Text(user.name, fontWeight = FontWeight.Bold) },
            supportingContent = { if(showSupportingContent) {
                if(user.deleteAt == null)
                    Text("Updated $lastUpdatedTime\nAt ${user.locationName}\n$sinceString")
                else {
                    Button({
                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clipData = android.content.ClipData.newPlainText("text", "https://findfamily.cc/view/${user.id}#key=${user.locationName}")
                        clipboardManager.setPrimaryClip(clipData)
                    }) {
                        Text("Copy link")
                    }
                }
            } }, trailingContent = { if(showSupportingContent && user.deleteAt == null){
                Text("$speed m/s")
            } })
    }
}

@Composable
fun BatteryBar(percent: Float, width: Dp = 30.dp, height: Dp = 15.dp) {
    val color = when {
        percent > 50 -> Color.Green
        percent > 20 -> Color.Yellow
        else -> Color.Red
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(width, height).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxHeight().width((width * (percent / 100f))).background(color, RoundedCornerShape(4.dp)))
        }
        Text("${percent.toInt()}%", fontSize = 12.sp)
    }
}

fun timestring(timestamp: Instant): String {
    val duration = Clock.System.now() - timestamp
    return if(duration.inWholeSeconds < 60) {
        "just now"
    } else if(duration.inWholeMinutes < 60) {
        "${duration.inWholeMinutes} minutes ago"
    } else if(duration.inWholeHours < 24) {
        "${duration.inWholeHours} hours ago"
    } else {
        "${duration.inWholeDays} days ago"
    }
}

object DateFormats {
    // example: Jun 4
    val MONTH_DAY = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        chars(" ")
        day()
    }

    // example: 10:05 am
    val TIME_SECOND_AM_PM = LocalTime.Format {
        amPmHour()
        chars(":")
        minute()
        chars(":")
        second()
        chars(" ")
        amPmMarker("AM", "PM")
    }

    // example: 05/12/2025
    val DATE_INPUT = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        chars(" ")
        day()
    }
}