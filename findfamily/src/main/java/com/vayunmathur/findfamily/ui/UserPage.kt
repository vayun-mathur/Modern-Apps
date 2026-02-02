package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.findfamily.Networking
import com.vayunmathur.findfamily.Platform
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.toPosition
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.pop
import com.vayunmathur.library.util.popThen
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPage(platform: Platform, backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, userId: Long) {
    val selectedUser by viewModel.get<User>(userId) {User.EMPTY}
    val locationValues by viewModel.data<LocationValue>().collectAsState()
    val userPositions by remember { derivedStateOf {
        locationValues.groupBy { it.userid }.mapValues { it.value.maxBy { it.timestamp } }
    } }

    var isShowingPresent by remember { mutableStateOf(true) }
    var historicalPosition by remember { mutableStateOf<Position?>(null) }

    val requestPickContact1 = platform.requestPickContact { name, photo ->
        viewModel.upsert(selectedUser.copy(name = name, photo = photo))
    }

    Scaffold(topBar = { TopAppBar({}, navigationIcon = { IconNavigation(backStack) }, actions = {
        if(userId != Networking.userid)
            IconButton({
                viewModel.delete(selectedUser)
                backStack.pop()
            }) {
                IconDelete()
            }
    }) }) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {

            Box(Modifier.fillMaxWidth().weight(1f)) {
                MapView(backStack, viewModel, navEnabled = true, selectedUser = SelectedUser(selectedUser, isShowingPresent, historicalPosition))

                HistoryBar(backStack, isShowingPresent, {isShowingPresent = it}, locationValues) {historicalPosition = it}
            }

            Surface(Modifier.heightIn(max = 400.dp)) {
                Column {
                    UserCard(backStack, platform, selectedUser, userPositions[selectedUser.id], true)
                    Spacer(Modifier.height(4.dp))
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Share your location")
                                Spacer(Modifier.weight(1f))
                                Checkbox(
                                    selectedUser.sendingEnabled,
                                    { send ->
                                        viewModel.upsert(selectedUser.copy(sendingEnabled = send))
                                    })
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton({
                            requestPickContact1()
                        }) {
                            Text("Change connected contact")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.HistoryBar(backStack: NavBackStack<Route>, isShowingPresent: Boolean, setShowingPresent: (Boolean) -> Unit, locs: List<LocationValue>, setHistoricalPosition: (Position) -> Unit) {
    Card(Modifier.width(105.dp).padding(2.dp).align(Alignment.BottomEnd)) {
        val colmod = if(isShowingPresent) Modifier else Modifier.fillMaxHeight(1f)
        Column(colmod.padding(4.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!isShowingPresent) {
                val currentDate = Clock.System.now().toLocalDateTime(
                    TimeZone.currentSystemDefault()
                ).date
                val currentTime = Clock.System.now().toLocalDateTime(
                    TimeZone.currentSystemDefault()
                ).time
                var pickedLocalDate by remember {
                    mutableStateOf(
                        Clock.System.now().toLocalDateTime(
                            TimeZone.currentSystemDefault()
                        ).date
                    )
                }
                val sliderState = rememberSliderState(
                    Clock.System.now().toLocalDateTime(
                        TimeZone.currentSystemDefault()
                    ).time.toSecondOfDay().toFloat(), valueRange = 0.0f..(24f*60f*60f-0.1f))

                LaunchedEffect(Unit) {
                    sliderState.onValueChange = {
                        val maximum = if(currentDate == pickedLocalDate) currentTime.toSecondOfDay().toFloat() else null
                        if(maximum != null && it > maximum) sliderState.value = maximum
                        else sliderState.value = it
                    }
                }
                val pickedLocalTime by remember {
                    derivedStateOf {
                        LocalTime.fromSecondOfDay(sliderState.value.toInt())
                    }
                }
                Box(Modifier.weight(1f)) {
                    VerticalSlider(sliderState, reverseDirection = true)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton({
                        sliderState.value -= 5*60
                    }) {
                        Text("<<<")
                    }
                    IconButton({
                        sliderState.value += 5*60
                    }) {
                        Text(">>>")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton({
                        sliderState.value -= 60
                    }) {
                        Text("<<")
                    }
                    IconButton({
                        sliderState.value += 60
                    }) {
                        Text(">>")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton({
                        sliderState.value -= 10
                    }) {
                        Text("<")
                    }
                    IconButton({
                        sliderState.value += 10
                    }) {
                        Text(">")
                    }
                }
                Text(pickedLocalTime.format(DateFormats.TIME_SECOND_AM_PM), fontSize = 11.sp)

                ResultEffect<LocalDate>("HistoryDatePicker") {
                    pickedLocalDate = it
                }

                OutlinedButton({
                    backStack.add(Route.UserPageHistoryDatePicker(pickedLocalDate))
                }, Modifier.fillMaxWidth()) {
                    Text(pickedLocalDate.format(DateFormats.DATE_INPUT))
                }
                val simulatedTimestamp = pickedLocalDate.atTime(pickedLocalTime)
                    .toInstant(TimeZone.currentSystemDefault())

                if (locs.isNotEmpty()) {
                    val points = locs.map { it.timestamp to it.coord }
                    val closest =
                        points.minBy { (it.first - simulatedTimestamp).absoluteValue }
                    setHistoricalPosition(closest.second.toPosition())
//                    LaunchedEffect(closest.first) {
//                        val newZoom = max(camera.position.zoom, 14.0)
//                        camera.animateTo(
//                            camera.position.copy(
//                                target = closest.second.toPosition(),
//                                zoom = newZoom
//                            )
//                        )
//                    }
                }
            }
            OutlinedButton({
                setShowingPresent(!isShowingPresent)
            }, Modifier.fillMaxWidth()) {
                Text(if (isShowingPresent) "History" else "Hide", fontSize = 11.sp)
            }
        }
    }
}