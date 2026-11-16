package com.vayunmathur.maps.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.util.SelectedFeatureViewModel

/** Zoom the cold-start camera adopts once the user's position is known. */
private const val FIRST_FIX_ZOOM = 14.0

/**
 * Cold-start camera for the map screen.
 *
 * Seeded from the OS-cached device fix (synchronous `LocationManager`
 * `getLastKnownLocation`), falling back to the persisted last fix. With neither,
 * the camera starts on the default world view until the first live fix centers
 * it below — never on an arbitrary city. DataStore's snapshot read can be empty
 * on a truly cold process (not yet hydrated), so the first live fix still
 * centers below — either way the zoom stays at the browse default.
 *
 * The centering runs once: it is skipped when a deep link or an existing
 * selection owns the camera, and latched in that case too, so consuming the
 * deep link later cannot re-trigger a center that would steal it back.
 */
@Composable
fun rememberInitialCamera(viewModel: SelectedFeatureViewModel): CameraState {
    val context = LocalContext.current
    val dataStore = remember(context) { DataStoreUtils.getInstance(context) }
    // Read once: the seed expression would otherwise re-query LocationManager
    // on every recomposition.
    val seed = remember(dataStore) {
        lastKnownDeviceLocation(context)
            ?: persistedLastFix(dataStore)
    }
    val camera = rememberCameraState(
        seed?.let { CameraPosition(target = it, zoom = FIRST_FIX_ZOOM) } ?: CameraPosition(),
    )

    val userPosition by viewModel.userPosition.collectAsState()
    val sheetRoute by viewModel.sheetRoute.collectAsState()
    val selectedFeature = sheetRoute
    val pendingFocus by viewModel.pendingFocus.collectAsState()
    var centeredOnFix by remember { mutableStateOf(false) }
    LaunchedEffect(userPosition, selectedFeature, pendingFocus) {
        if (centeredOnFix) return@LaunchedEffect
        if (userPosition.latitude == 0.0 && userPosition.longitude == 0.0) return@LaunchedEffect
        if (pendingFocus != null || selectedFeature != null) {
            centeredOnFix = true
            return@LaunchedEffect
        }
        centeredOnFix = true
        camera.animateTo(
            camera.position.copy(
                target = userPosition,
                zoom = FIRST_FIX_ZOOM.coerceAtLeast(camera.position.zoom),
            ),
        )
    }
    return camera
}

/** The persisted last fix, or null when no fix was ever stored. */
private fun persistedLastFix(dataStore: DataStoreUtils): GeoPoint? {
    val lon = dataStore.getDouble(MapPreferences.KEY_LAST_LON) ?: return null
    val lat = dataStore.getDouble(MapPreferences.KEY_LAST_LAT) ?: return null
    if (lat == 0.0 && lon == 0.0) return null
    return GeoPoint(lon, lat)
}

/**
 * Best cached device fix, or null if none is available yet. Reads the synchronous
 * [LocationManager.getLastKnownLocation] cache — no fix wait — so the map can open
 * centred on the user immediately. Newest of GPS/network wins; a (0,0) cached
 * fix is the OS having nothing real, not the Gulf of Guinea.
 */
private fun lastKnownDeviceLocation(context: Context): GeoPoint? {
    val hasFine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null
    // The map sits behind an AppPermissionsGate, so permission is granted in practice;
    // a SecurityException (provider state changed mid-call) reads as no fix.
    return try {
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gps = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()
        val network = runCatching {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        val best = listOfNotNull(gps, network).maxByOrNull { it.time }
        best?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
            ?.let { GeoPoint(it.longitude, it.latitude) }
    } catch (_: SecurityException) {
        null
    }
}
