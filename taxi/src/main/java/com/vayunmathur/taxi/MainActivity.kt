package com.vayunmathur.taxi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.taxi.data.BookingTrip
import com.vayunmathur.taxi.data.RemoteImageFetchers
import com.vayunmathur.taxi.ipc.RideHandoffContract
import com.vayunmathur.taxi.notifications.RideLiveUpdate

class MainActivity : MaActivity() {
    companion object {
        // Hosts the cross-app AssistantIntent client for taxi (mirrors
        // openassistant MainActivity.intentLauncher). WS-B's DirectionsClient and
        // WS-C's RideEstimate/OrderLookup callers launch intents through this.
        lateinit var intentLauncher: IntentLauncher
    }

    // The ride to open on the tracking screen, set from a notification tap. Held as Compose
    // state so onNewIntent can push a new deep link into the running UI.
    private val trackRideId = mutableStateOf<String?>(null)

    // A trip to pre-fill on the ride screen, set from a `taxi://book` deep link (e.g. the maps
    // route sheet "Book ride" button). Held as Compose state so onNewIntent can push a new trip
    // into the running UI.
    private val bookingTrip = mutableStateOf<BookingTrip?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreateBeforeContent() {
        NetworkClient.init(this, TrustBundle.STANDARD)
        // Driver photos load over HTTP(S) through the shared image pipeline;
        // the fetch function is built here from NetworkClient so no
        // library-to-library edge is needed (#884).
        ImageLoader.setDefault(
            ImageLoader.Builder(this).remote(RemoteImageFetchers::network).build(),
        )
        intentLauncher = IntentLauncher(this)

        trackRideId.value = intent.trackRideIdOrNull()
        bookingTrip.value = intent.bookingTripOrNull()
        requestNotificationPermissionIfNeeded()
    }

    @Composable
    override fun Content() {
        Navigation(trackRideId, bookingTrip)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.trackRideIdOrNull()?.let { trackRideId.value = it }
        intent.bookingTripOrNull()?.let { bookingTrip.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun Intent.trackRideIdOrNull(): String? =
        getStringExtra(RideLiveUpdate.EXTRA_TRACK_RIDE_ID)?.takeIf { it.isNotBlank() }

    // taxi://book?pickup_lat=&pickup_lng=&dest_lat=&dest_lng=(&labels…) — the cross-app
    // "start a booking with this trip pre-filled" deep link (see RideHandoffContract). Returns
    // null for anything else so a bad/other link degrades to a plain launch.
    private fun Intent.bookingTripOrNull(): BookingTrip? =
        RideHandoffContract.parseBooking(data?.toString())
}
