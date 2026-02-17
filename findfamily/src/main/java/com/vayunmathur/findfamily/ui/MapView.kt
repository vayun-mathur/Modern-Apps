package com.vayunmathur.findfamily.ui

import android.net.Network
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.findfamily.Networking
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.radians
import com.vayunmathur.findfamily.data.toPosition
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.pop
import com.vayunmathur.library.util.reset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.Position
import kotlin.math.abs
import kotlin.math.cos
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

val camera = CameraState(CameraPosition())

data class SelectedUser(val user: User, val isShowingPresent: Boolean, val historicalPosition: Position?)
data class SelectedWaypoint(val waypoint: Waypoint, val range: Double, val onMoveWaypoint: (Coord) -> Unit)

private fun Position.toCoord() = Coord(latitude, longitude)

@Composable
fun MapView(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    navEnabled: Boolean,
    selectedUser: SelectedUser? = null,
    selectedWaypoint: SelectedWaypoint? = null,
) {
    val users by viewModel.data<User>().collectAsState()
    val waypoints by viewModel.data<Waypoint>().collectAsState()
    val locationValues by viewModel.data<LocationValue>().collectAsState()
    val userPositions by remember { derivedStateOf {
        locationValues.groupBy { it.userid }.mapValues { it.value.maxBy { it.timestamp } }
    } }

    LaunchedEffect(Unit) {
        delay(1000)
        if (users.isEmpty()) {
            withContext(Dispatchers.IO) {
                viewModel.upsert(
                    User(
                        "Me",
                        null,
                        "Unnamed Location",
                        true,
                        RequestStatus.MUTUAL_CONNECTION,
                        Clock.System.now(),
                        null,
                        Networking.userid
                    )
                )
            }
        }
    }

    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            camera.awaitProjection()
            initialized = true
        }
    }


    var sizeInDp by remember { mutableStateOf(DpOffset(0.dp, 0.dp)) }
    val density = LocalDensity.current
    
    Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        // Size is in Pixels
        val sizePx = coordinates.size

        // Convert Pixels to DP
        sizeInDp = with(density) {
            DpOffset(
                x = sizePx.width.toDp()/2,
                y = sizePx.height.toDp()/2
            )
        }
    }) {
        MaplibreMap(
            Modifier,
            BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
            camera,
            0f..20f,
            options = MapOptions(
                gestureOptions = GestureOptions(false, true, false, true),
                ornamentOptions = OrnamentOptions.AllDisabled
            ),
            onMapClick = { _, offset ->
                backStack.reset(Route.MainPage)
                ClickResult.Pass
            },
        )

        if(initialized) {
            key(camera.position, sizeInDp) {
                Canvas(Modifier.fillMaxSize()) {
                    val allWaypoints = if(selectedWaypoint?.waypoint?.id == 0L) (waypoints + selectedWaypoint.waypoint) else waypoints
                    for (waypoint in allWaypoints) {
                        val radiusMeters = if(selectedWaypoint?.waypoint == waypoint) selectedWaypoint.range else waypoint.range
                        val coord = if(selectedWaypoint?.waypoint == waypoint) {
                            val c = camera.projection!!.positionFromScreenLocation(sizeInDp).toCoord()
                            selectedWaypoint.onMoveWaypoint(c)
                            c
                        } else waypoint.coord
                        val center = camera.projection!!.screenLocationFromPosition(coord.toPosition())
                        if(center !in size.toDpSize()) continue
                        val circumferenceAtLatitude =
                            40_075_000 * cos(radians(waypoint.coord.lat))
                        val radiusInDegrees = 360 * radiusMeters / circumferenceAtLatitude
                        val edgePoint = camera.projection!!.screenLocationFromPosition(
                            Position(coord.lon + radiusInDegrees, coord.lat)
                        )
                        val radiusPx = abs((center.x - edgePoint.x).toPx())
                        Circle(
                            center.toOffset(this),
                            Color(0x80Add8e6),
                            Color(0xffAdd8e6),
                            radiusPx
                        )
                    }
                }
                for (user in users) {
                    if(selectedUser != null && user.id != selectedUser.user.id) continue
                    val position = userPositions[user.id]?.coord?.toPosition() ?: continue
                    val center =
                        camera.projection!!.screenLocationFromPosition(position) - DpOffset(35.dp, 35.dp)

                    Box(Modifier.offset(center.x, center.y)) {
                        UserPicture(user, 70.dp, selectedUser != null && !selectedUser.isShowingPresent) {
                            if(navEnabled) {
                                backStack.add(Route.UserPage(user.id))
                            }
                        }
                    }
                }
                if(selectedUser != null && !selectedUser.isShowingPresent && selectedUser.historicalPosition != null) {
                    val center =
                        camera.projection!!.screenLocationFromPosition(selectedUser.historicalPosition) - DpOffset(35.dp, 35.dp)

                    Box(Modifier.offset(center.x, center.y)) {
                        UserPicture(selectedUser.user, 70.dp)
                    }
                }
            }
        }
    }
}

fun DrawScope.Circle(position: Offset, color: Color, borderColor: Color, radius: Float) {
    drawCircle(color, radius, position)
    drawCircle(borderColor, radius, position, style = Stroke(width = radius/20))
}

private operator fun DpSize.contains(offset: DpOffset): Boolean {
    return offset.x in 0.dp..width && offset.y in 0.dp..height
}

private fun DpOffset.toOffset(density: Density): Offset {
    with(density) {
        return Offset(x.toPx(), y.toPx())
    }
}

@Composable
fun UserPicture(user: User, size: Dp, grayscale: Boolean = false, onClick: () -> Unit = {}) {
    UserPicture(user.photo, user.name.first(), size, grayscale, onClick)
}

@Composable
fun GreenCircle(size: Dp, char: Char? = null, grayscale: Boolean = false, onClick: () -> Unit = {}) {
    Box(Modifier.clip(CircleShape).size(size).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).background(if(grayscale)Color.Gray else Color.Green ).invisibleClickable(onClick)) {
        char?.let {
            Text(char.toString(), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
        }
    }
}

val ColorFilter.Companion.GrayScale: ColorFilter
    get() = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
fun UserPicture(userPhoto: String?, firstChar: Char, size: Dp, grayscale: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.clip(CircleShape).size(size).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).invisibleClickable(onClick)
    if(userPhoto != null)
        AsyncImage(userPhoto, null, modifier, contentScale = ContentScale.FillWidth, colorFilter = if(grayscale) ColorFilter.GrayScale else null)
    else {
        GreenCircle(size, firstChar, grayscale, onClick = onClick)
    }
}