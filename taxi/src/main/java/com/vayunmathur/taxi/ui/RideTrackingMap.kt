package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.GestureOptions
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconMyLocation
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.RideStopInfo
import kotlin.math.max
import kotlin.math.min

/**
 * Pickup, destination and the live driver on a raster map. Markers are absolutely positioned from
 * the camera projection; the view auto-frames whatever points are known.
 */
@Composable
internal fun TrackingMap(ride: ActiveRide?, driverLoc: DriverLocation?, modifier: Modifier = Modifier) {
    val stops = ride?.stops ?: emptyList()
    val pickup = stops.firstOrNull { it.isPickup }?.geoPoint() ?: stops.firstOrNull()?.geoPoint()
    val dropoff = stops.firstOrNull { it.isDropoff }?.geoPoint() ?: stops.lastOrNull()?.geoPoint()
    val driver = driverLoc?.let { GeoPoint(it.longitude, it.latitude) }
    val points = listOfNotNull(pickup, dropoff, driver)

    if (points.isEmpty()) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        return
    }

    val centre = GeoPoint(
        longitude = points.sumOf { it.longitude } / points.size,
        latitude = points.sumOf { it.latitude } / points.size,
    )
    val spread = max(
        points.maxOf { it.longitude } - points.minOf { it.longitude },
        points.maxOf { it.latitude } - points.minOf { it.latitude },
    )
    val zoom = when {
        spread <= 0.0 -> 15.0
        else -> min(16.0, max(10.0, Math.log(360.0 / spread) / Math.log(2.0)))
    }
    val camera = rememberCameraState(CameraPosition(target = centre, zoom = zoom))
    LaunchedEffect(centre, zoom) {
        camera.position = CameraPosition(target = centre, zoom = zoom)
    }

    Box(modifier) {
        VectorMap(cameraState = camera, options = MapOptions(gestureOptions = GestureOptions.TiltLocked), online = ConnectivityMonitor.isOnline) {
            pickup?.let {
                MapMarker(it) {
                    PinChrome(MaterialTheme.colorScheme.tertiary) { IconMyLocation(tint = Color.White) }
                }
            }
            dropoff?.let {
                MapMarker(it) {
                    PinChrome(MaterialTheme.colorScheme.primary) { IconHome(tint = Color.White) }
                }
            }
            driver?.let {
                MapMarker(it) {
                    PinChrome(MaterialTheme.colorScheme.secondary) {
                        IconNavigationArrow(
                            tint = Color.White,
                            modifier = Modifier.rotate((driverLoc.bearing ?: 0.0).toFloat()),
                        )
                    }
                }
            }
        }
    }
}

internal fun RideStopInfo.geoPoint(): GeoPoint? =
    location?.let { GeoPoint(it.longitude, it.latitude) }

/**
 * The circular disc a pin's icon sits on. Stays app-side rather than moving into
 * `:library:map` with the positioning: it reads `colorScheme`, and that module has no
 * material3.
 */
@Composable
internal fun PinChrome(color: Color, icon: @Composable () -> Unit) {
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}
