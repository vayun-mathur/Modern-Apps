package com.vayunmathur.networklocation.ui

import android.location.Address
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.networklocation.platform.hasFineLocation
import com.vayunmathur.networklocation.platform.locationUpdates
import com.vayunmathur.networklocation.platform.reverseGeocode
import kotlinx.coroutines.delay
import java.util.Locale

/** Re-geocode only after the GPS fix has moved this far, not on every one-second fix. */
private const val REGEOCODE_DISTANCE_M = 25f
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val TICK_MS = 1_000L

private sealed interface GeocodeState {
    data object WaitingForGps : GeocodeState
    data object Looking : GeocodeState
    data class Found(val address: Address, val from: Location) : GeocodeState
    data object NoMatch : GeocodeState
    data class Failed(val message: String) : GeocodeState
}

/**
 * Live check of both halves of the app: the GPS fix, this app's own network-location fix, and
 * the GPS fix run through the offline geocoder. GPS rather than the network fix is geocoded so
 * the geocoder is judged against a position that does not depend on the beacon stores.
 */
@Composable
fun LiveLocationSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!hasFineLocation(context)) {
        Text(
            "Location permission is not granted, so live positions cannot be shown.",
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    val gpsFlow = remember { locationUpdates(context, LocationManager.GPS_PROVIDER) }
    val networkFlow = remember { locationUpdates(context, LocationManager.NETWORK_PROVIDER) }
    val gps by gpsFlow.collectAsState(initial = null)
    val network by networkFlow.collectAsState(initial = null)

    // Fix ages are shown live, so recompose once a second.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = SystemClock.elapsedRealtimeNanos()
        }
    }

    var anchor by remember { mutableStateOf<Location?>(null) }
    LaunchedEffect(gps) {
        val fix = gps ?: return@LaunchedEffect
        if (anchor?.let { it.distanceTo(fix) > REGEOCODE_DISTANCE_M } != false) anchor = fix
    }
    var geocode by remember { mutableStateOf<GeocodeState>(GeocodeState.WaitingForGps) }
    LaunchedEffect(anchor) {
        val fix = anchor ?: return@LaunchedEffect
        geocode = GeocodeState.Looking
        geocode = reverseGeocode(context, fix.latitude, fix.longitude).fold(
            onSuccess = { a -> a?.let { GeocodeState.Found(it, fix) } ?: GeocodeState.NoMatch },
            onFailure = { GeocodeState.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    Column(modifier) {
        Text("Live check", style = MaterialTheme.typography.titleMedium)
        FixCard("GPS", gps, now, emptyText = "Waiting for a GPS fix… (go outside or near a window)")
        FixCard(
            "Network location (this app)",
            network,
            now,
            emptyText = "No network fix yet. Cell and Wi-Fi scans resolve against the offline stores.",
            comparedTo = gps,
        )
        GeocodeCard(geocode)
    }
}

@Composable
private fun FixCard(
    title: String,
    fix: Location?,
    now: Long,
    emptyText: String,
    comparedTo: Location? = null,
) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (fix == null) {
                Text(emptyText, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(
                coords(fix.latitude, fix.longitude),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            val details = buildList {
                if (fix.hasAccuracy()) add("±%.0f m".format(Locale.US, fix.accuracy))
                add("${(now - fix.elapsedRealtimeNanos).coerceAtLeast(0) / NANOS_PER_SECOND} s ago")
                comparedTo?.let { add("%.0f m from GPS".format(Locale.US, it.distanceTo(fix))) }
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GeocodeCard(state: GeocodeState) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Offline geocoder (GPS position)", style = MaterialTheme.typography.titleSmall)
            val body = Modifier.padding(top = 4.dp)
            when (state) {
                GeocodeState.WaitingForGps ->
                    Text("Waiting for a GPS fix to look up.", body, style = MaterialTheme.typography.bodyMedium)
                GeocodeState.Looking -> Text(
                    "Looking up… the first lookup after the service starts can take a while " +
                        "while the database opens.",
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                )
                GeocodeState.NoMatch ->
                    Text("No nearby record in the database.", body, style = MaterialTheme.typography.bodyMedium)
                is GeocodeState.Failed ->
                    Text("Lookup failed: ${state.message}", body, style = MaterialTheme.typography.bodyMedium)
                is GeocodeState.Found -> FoundAddress(state.address, state.from)
            }
        }
    }
}

@Composable
private fun FoundAddress(a: Address, from: Location) {
    Text(
        a.getAddressLine(0) ?: a.featureName ?: "(unnamed)",
        Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
    val parts = listOfNotNull(a.thoroughfare, a.locality, a.adminArea, a.postalCode, a.countryCode)
    if (parts.isNotEmpty()) Text(parts.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
    val match = FloatArray(1)
    Location.distanceBetween(from.latitude, from.longitude, a.latitude, a.longitude, match)
    Text(
        "Record at ${coords(a.latitude, a.longitude)} · %.0f m from the GPS fix".format(Locale.US, match[0]),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun coords(lat: Double, lon: Double) = "%.6f, %.6f".format(Locale.US, lat, lon)
