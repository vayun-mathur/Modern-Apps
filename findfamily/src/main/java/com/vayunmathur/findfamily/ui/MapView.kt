package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.radians
import com.vayunmathur.findfamily.data.toGeoPoint
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.library.ui.invisibleClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.GestureOptions
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.util.ConnectivityMonitor
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.cos

data class SelectedUser(val user: User, val isShowingPresent: Boolean, val historicalPosition: GeoPoint?)
data class SelectedWaypoint(val waypoint: Waypoint, val range: Double, val onMoveWaypoint: (Coord) -> Unit)

private fun GeoPoint.toCoord() = Coord(latitude, longitude)

@Composable
fun MapView(
    viewModel: FindFamilyViewModel,
    camera: CameraState,
    onUserClick: (Long) -> Unit,
    onMapClick: () -> Unit,
    selectedUser: SelectedUser? = null,
    selectedWaypoint: SelectedWaypoint? = null,
) {
    val users by viewModel.users.collectAsState()
    val waypoints by viewModel.waypoints.collectAsState()
    val userPositions by viewModel.latestLocationByUser.collectAsState()

    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            camera.awaitProjection()
            initialized = true
        }
    }

    var sizeInDp by remember { mutableStateOf(DpOffset(0.dp, 0.dp)) }
    val density = LocalDensity.current
    // Waypoint geofence fill/border follow Material You (issue #816) instead of
    // the old hardcoded light-blue, so they match the rest of the UI.
    val waypointFill = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    val waypointBorder = MaterialTheme.colorScheme.primary

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            // Size is in Pixels
            val sizePx = coordinates.size

            // Convert Pixels to DP
            sizeInDp = with(density) {
                DpOffset(
                    x = sizePx.width.toDp() / 2,
                    y = sizePx.height.toDp() / 2
                )
            }
        }) {
            VectorMap(
                cameraState = camera,
                zoomRange = 0f..20f,
                options = MapOptions(gestureOptions = GestureOptions.TiltLocked),
                onMapClick = { onMapClick() },
                online = ConnectivityMonitor.isOnline,
            ) {
                if (initialized) {
                    // The coordinate at the screen centre — i.e. the live position of
                    // the waypoint currently being placed/dragged. Computed outside the
                    // Canvas draw scope so we never invoke callbacks while drawing.
                    val draggedCoord = remember(camera.position, sizeInDp) {
                        camera.projection?.positionFromScreenLocation(sizeInDp)?.toCoord()
                    }
                    // Report the dragged position back to the parent outside of drawing.
                    if (selectedWaypoint != null && draggedCoord != null) {
                        LaunchedEffect(draggedCoord) {
                            selectedWaypoint.onMoveWaypoint(draggedCoord)
                        }
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        // Read inside the draw lambda, so a camera move re-runs drawing only.
                        // This used to be three `camera.projection!!` reads wrapped in a
                        // `key(camera.position, sizeInDp)` that threw the whole subtree away
                        // every frame of a pan — and crashed outright in the frames before
                        // the viewport was measured.
                        val proj = projection ?: return@Canvas
                        val allWaypoints =
                            if (selectedWaypoint?.waypoint?.id == 0L) (waypoints + selectedWaypoint.waypoint) else waypoints
                        for (waypoint in allWaypoints) {
                            val radiusMeters =
                                if (selectedWaypoint?.waypoint == waypoint) selectedWaypoint.range else waypoint.range
                            val coord = if (selectedWaypoint?.waypoint == waypoint) {
                                draggedCoord ?: waypoint.coord
                            } else waypoint.coord
                            val center = proj.screenLocationFromPosition(coord.toGeoPoint())
                            if (center !in size.toDpSize()) continue
                            val circumferenceAtLatitude =
                                40_075_000 * cos(radians(waypoint.coord.lat))
                            val radiusInDegrees = 360 * radiusMeters / circumferenceAtLatitude
                            val edgePoint = proj.screenLocationFromPosition(
                                GeoPoint(coord.lon + radiusInDegrees, coord.lat)
                            )
                            val radiusPx = abs((center.x - edgePoint.x).toPx())
                            drawCircleWithBorder(
                                center.toOffset(this),
                                waypointFill,
                                waypointBorder,
                                radiusPx
                            )
                        }
                    }
                    for (user in users) {
                        if (selectedUser != null && user.id != selectedUser.user.id) continue
                        val position = userPositions[user.id]?.coord?.toGeoPoint() ?: continue
                        MapMarker(position) {
                            UserPicture(
                                user,
                                70.dp,
                                selectedUser != null && !selectedUser.isShowingPresent
                            ) {
                                onUserClick(user.id)
                            }
                        }
                    }
                    if (selectedUser != null && !selectedUser.isShowingPresent && selectedUser.historicalPosition != null) {
                        MapMarker(selectedUser.historicalPosition) {
                            UserPicture(selectedUser.user, 70.dp)
                        }
                    }
                }
            }
        }
    }
}

fun DrawScope.drawCircleWithBorder(position: Offset, color: Color, borderColor: Color, radius: Float) {
    drawCircle(color, radius, position)
    drawCircle(borderColor, radius, position, style = Stroke(width = radius/20))
}

private operator fun DpSize.contains(offset: DpOffset): Boolean {
    return offset.x in 0.dp..width && offset.y in 0.dp..height
}

private fun DpOffset.toOffset(density: Density): Offset = with(density) {
    Offset(x.toPx(), y.toPx())
}

@Composable
fun UserPicture(user: User, size: Dp, grayscale: Boolean = false, onClick: () -> Unit = {}) {
    UserPicture(user.photo, user.name.first(), size, grayscale, onClick)
}

@Composable
fun GreenCircle(size: Dp, char: Char? = null, grayscale: Boolean = false, onClick: () -> Unit = {}) {
    Box(Modifier.clip(CircleShape).size(size).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape).background(if(grayscale) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer).invisibleClickable(onClick)) {
        char?.let {
            Text(char.toString(), Modifier.align(Alignment.Center), color = if(grayscale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

val ColorFilter.Companion.GrayScale: ColorFilter
    get() = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
fun UserPicture(userPhoto: String?, firstChar: Char, size: Dp, grayscale: Boolean, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val modifier = Modifier.clip(CircleShape).size(size).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).invisibleClickable(onClick)
    if(userPhoto != null) {
        val userOutput: Any = if(userPhoto.startsWith("data")) {
            val cleanString = userPhoto.substringAfter("base64,")
            Base64.UrlSafe.decode(cleanString)
        } else userPhoto
        AsyncImage(
            ImageRequest.Builder(context)
                .data(userOutput)
                .memoryCacheKey("user-photo-${userPhoto.hashCode()}")
                .build(),
            null,
            modifier,
            contentScale = ContentScale.FillWidth,
            colorFilter = if (grayscale) ColorFilter.GrayScale else null
        )
    } else {
        GreenCircle(size, firstChar, grayscale, onClick = onClick)
    }
}
