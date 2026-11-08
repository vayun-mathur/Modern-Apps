package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.taxi.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.network.lyft.LyftProvider
import com.vayunmathur.taxi.notifications.RideTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live tracking for one ride: the driver's position against the pickup and destination, the
 * current status, an ETA, and the driver/vehicle details — laid out as a full-bleed map with a
 * rounded card floating over the bottom (mirrors fooddelivery's OrderTrackingScreen).
 *
 * Polls the active ride (~5s) for status/ETA and the driver location (~3s) for smoother marker
 * movement, stopping once the ride is terminal. No navigation action cancels or ends the ride —
 * only the explicit Cancel button does.
 */
@Composable
fun RideTrackingScreen(rideId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember { LyftProvider(context.applicationContext) }

    var ride by remember { mutableStateOf<ActiveRide?>(null) }
    var liveLocation by remember { mutableStateOf<DriverLocation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }
    var confirmingCancel by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var cancelMessage by remember { mutableStateOf<String?>(null) }

    fun isDone() = ended || ride?.status?.isTerminal == true

    // Active-ride poll: status, driver/vehicle details, per-stop ETA.
    LaunchedEffect(rideId) {
        // Keep the background Live Update tracker running for this ride too, so tracking
        // opened from the Current ride tab (or a re-opened app) is covered. Idempotent.
        RideTrackingService.start(context, rideId)
        while (true) {
            when (val result = provider.activeRide()) {
                is RideStatusResult.Active -> {
                    ride = result.ride
                    error = null
                }
                RideStatusResult.None -> {
                    // The active-ride endpoint may not surface a just-booked ride; fall back to
                    // reading it directly by id before giving up.
                    when (val byId = provider.rideById(rideId)) {
                        is RideStatusResult.Active -> {
                            ride = byId.ride
                            error = null
                        }
                        else -> ended = true
                    }
                }
                is RideStatusResult.Failed -> error = result.message
                RideStatusResult.Unsupported -> ended = true
            }
            if (isDone()) break
            delay(5_000)
        }
    }

    // Higher-frequency driver-location poll for smoother marker movement.
    LaunchedEffect(rideId) {
        while (true) {
            if (isDone()) break
            provider.driverLocation(rideId)?.let { liveLocation = it }
            delay(3_000)
        }
    }

    val driverLoc = liveLocation ?: ride?.driverLocation

    AppScaffold(title = stringResource(R.string.tracking_title), scrollBehavior = appBarScrollBehavior()) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            TrackingMap(ride, driverLoc, Modifier.fillMaxSize())

            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                tonalElevation = 3.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .padding(bottom = padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val current = ride
                    when {
                        current != null -> {
                            StatusHeader(current, ended)
                            DriverCard(
                                current,
                                onCall = { phone -> ExternalIntents.dial(context, phone) },
                            )
                            if (!isDone()) {
                                Button(
                                    onClick = { confirmingCancel = true },
                                    enabled = !cancelling,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (cancelling) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.cancelling))
                                    } else {
                                        Text(stringResource(R.string.cancel_ride))
                                    }
                                }
                            }
                        }

                        error != null ->
                            Text(
                                stringResource(R.string.tracking_error, error!!),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )

                        else ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.tracking_loading))
                            }
                    }
                }
            }
        }
    }

    if (confirmingCancel) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = { Text(stringResource(R.string.cancel_ride_title)) },
            text = { Text(stringResource(R.string.cancel_ride_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingCancel = false
                    cancelling = true
                    scope.launch {
                        val result = provider.cancelRide(rideId)
                        cancelMessage = when (result) {
                            is CancelResult.Done -> context.getString(R.string.ride_cancelled)
                            is CancelResult.Failed -> context.getString(R.string.cancel_failed, result.message)
                            CancelResult.Unsupported ->
                                context.getString(R.string.cancel_failed, "unsupported")
                        }
                        cancelling = false
                        // A failed cancel leaves the ride live: only a confirmed
                        // cancel ends tracking, so the user can retry after a
                        // transient failure instead of being stranded on a
                        // screen that claims the ride ended.
                        if (result is CancelResult.Done) ended = true
                    }
                }) {
                    Text(stringResource(R.string.cancel_ride), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingCancel = false }) {
                    Text(stringResource(R.string.keep_ride))
                }
            },
        )
    }

    cancelMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { cancelMessage = null },
            title = { Text(message) },
            confirmButton = {
                TextButton(onClick = { cancelMessage = null }) { Text(stringResource(UiR.string.close)) }
            },
        )
    }
}
