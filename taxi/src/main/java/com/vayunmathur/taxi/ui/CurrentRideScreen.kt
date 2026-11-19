package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.network.lyft.LyftProvider

/**
 * The "Current ride" tab. Checks for an in-progress ride once; if there is one, shows live
 * tracking, otherwise a simple "no ride" message. Deliberately minimal state — no cross-tab
 * navigation or ride mutations happen here beyond the explicit Cancel button inside tracking.
 */
@Composable
fun CurrentRideScreen() {
    val context = LocalContext.current
    var rideId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val lyft = LyftProvider(context.applicationContext)
        rideId = if (lyft.isSignedIn()) {
            (lyft.activeRide() as? RideStatusResult.Active)?.ride?.rideId
        } else {
            null
        }
        loading = false
    }

    val id = rideId
    if (id != null) {
        RideTrackingScreen(rideId = id)
        return
    }

    AppScaffold(title = stringResource(R.string.nav_current_ride)) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    stringResource(R.string.no_current_ride),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
