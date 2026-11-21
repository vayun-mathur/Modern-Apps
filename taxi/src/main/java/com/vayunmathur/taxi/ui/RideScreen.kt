package com.vayunmathur.taxi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.RouteOverlay
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.BookingTrip
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.data.lyft.PendingRideAction
import com.vayunmathur.taxi.data.lyft.pendingRideAction
import com.vayunmathur.taxi.network.lyft.LyftProvider
import com.vayunmathur.taxi.notifications.RideTrackingService
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.intents.DirectionsClient
import com.vayunmathur.taxi.platform.deeplink.RideDeepLinks
import com.vayunmathur.taxi.platform.location.LocationProvider
import com.vayunmathur.taxi.provider.QuoteRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln

internal val PickupDotColor = Color(0xFF2FBF71)
internal val DestinationPinColor = Color(0xFFE23744)

internal fun providerColor(provider: Provider): Color = when (provider) {
    Provider.LYFT -> Color(0xFFEA0B8C)
    Provider.UBER -> Color(0xFF276EF1)
}

internal enum class RouteField { PICKUP, DESTINATION }

@Composable
fun RideScreen(bookingTrip: MutableState<BookingTrip?>? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val camera = rememberCameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 12.0))
    val isDark = isSystemInDarkTheme()

    var calculatedPickup by remember { mutableStateOf<Place?>(null) }
    var pickup by remember { mutableStateOf<Place?>(null) }
    var pickupQuery by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf<Place?>(null) }
    var destinationQuery by remember { mutableStateOf("") }
    var active by remember { mutableStateOf<RouteField?>(null) }
    var suggestions by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<Provider, QuoteResult>>(emptyMap()) }
    var comparing by remember { mutableStateOf(false) }
    var booking by remember { mutableStateOf<LyftBookingRequest?>(null) }
    var routeOverlay by remember { mutableStateOf<RouteOverlay?>(null) }

    // Lyft cost tokens expire; re-quote shortly before the soonest one lapses so a booking never
    // uses a stale token. Re-runs whenever results change (each refresh reschedules the next).
    LaunchedEffect(results, pickup, destination) {
        val from = pickup ?: return@LaunchedEffect
        val to = destination ?: return@LaunchedEffect
        val success = results[Provider.LYFT] as? QuoteResult.Success ?: return@LaunchedEffect
        val soonestExpiry = success.quotes.mapNotNull { it.costTokenExpiryMs }.minOrNull()
            ?: return@LaunchedEffect
        // Only schedule when the value looks like a future epoch-ms deadline; otherwise skip to
        // avoid a tight refresh loop on an unexpected unit.
        val leadMs = soonestExpiry - System.currentTimeMillis() - 30_000
        if (leadMs <= 0) return@LaunchedEffect
        delay(leadMs)
        results = QuoteRepository.refresh(context, from, to, results)
    }

    LaunchedEffect(Unit) {
        val here = LocationProvider.current(context) ?: return@LaunchedEffect
        val place = LocationProvider.describe(context, here)
        calculatedPickup = place
        if (pickup == null) {
            pickup = place
            pickupQuery = place.name
        }
        camera.animateTo(CameraPosition(GeoPoint(here.longitude, here.latitude), 14.0))
    }

    // Launch reconciliation for a booking interrupted by process death: the offer
    // was persisted before createRide, so check for a ride the server already
    // created before the user can book again. An active ride restores tracking
    // (and the trip inputs) instead of a duplicate booking; an expired offer is
    // discarded; a still-valid offer with no ride yet stays pending for the next
    // launch (activeRide can lag a just-booked ride) and is never auto-re-booked.
    LaunchedEffect(Unit) {
        val store = LyftTokenStore(context.applicationContext)
        val pending = store.pendingRide() ?: return@LaunchedEffect
        val provider = LyftProvider(context.applicationContext)
        val active = provider.activeRide() as? RideStatusResult.Active
        when (pendingRideAction(pending, active != null, System.currentTimeMillis())) {
            PendingRideAction.RestoreTracking -> {
                store.clearPendingRide()
                pickup = pending.pickup()
                pickupQuery = pending.pickupName
                destination = pending.dropoff()
                destinationQuery = pending.dropoffName
                RideTrackingService.start(context, active?.ride?.rideId)
            }
            PendingRideAction.Discard -> store.clearPendingRide()
            PendingRideAction.KeepPending -> {
                pickup = pending.pickup()
                pickupQuery = pending.pickupName
                destination = pending.dropoff()
                destinationQuery = pending.dropoffName
            }
        }
    }

    // Debounced lookup for whichever field is currently being edited.
    LaunchedEffect(active, pickupQuery, destinationQuery) {
        val field = active
        if (field == null) {
            suggestions = emptyList()
            searching = false
            return@LaunchedEffect
        }
        val q = (if (field == RouteField.PICKUP) pickupQuery else destinationQuery).trim()
        val selected = if (field == RouteField.PICKUP) pickup?.name else destination?.name
        if (q.isEmpty() || q == selected) {
            suggestions = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        suggestions = LocationProvider.search(context, q)
        searching = false
    }

    // Ask the maps app to plan the driving route for the selected trip, draw it inside the map
    // (coloured by traffic the way maps draws it), and frame the camera to the whole route.
    // Degrades to no route when maps is absent or the plan fails. Re-runs on either endpoint.
    LaunchedEffect(pickup, destination) {
        val from = pickup
        val to = destination
        if (from == null || to == null) {
            routeOverlay = null
            return@LaunchedEffect
        }
        val result = DirectionsClient.directions(
            context,
            fromLat = from.location.latitude,
            fromLng = from.location.longitude,
            toLat = to.location.latitude,
            toLng = to.location.longitude,
        )
        routeOverlay = result?.toRouteOverlay(isDark)
        val points = result?.polyline?.map { GeoPoint(it.lng, it.lat) }.orEmpty()
        routeBoundsCamera(points)?.let { camera.animateTo(it) }
    }

    fun quote() {
        val from = pickup ?: return
        val to = destination ?: return
        scope.launch {
            comparing = true
            // Frame both endpoints: centre between them, zoom from how far apart they are.
            val centre = GeoPoint(
                (from.location.longitude + to.location.longitude) / 2.0,
                (from.location.latitude + to.location.latitude) / 2.0,
            )
            val spread = maxOf(
                abs(from.location.longitude - to.location.longitude),
                abs(from.location.latitude - to.location.latitude),
            )
            val zoom = if (spread <= 0.0) 14.0 else (ln(360.0 / spread) / ln(2.0) - 1.0).coerceIn(10.0, 15.0)
            camera.animateTo(CameraPosition(centre, zoom))
            results = QuoteRepository.quotes(context, from, to)
            comparing = false
        }
    }

    fun select(place: Place) {
        when (active) {
            RouteField.PICKUP -> {
                pickup = place
                pickupQuery = place.name
            }
            RouteField.DESTINATION -> {
                destination = place
                destinationQuery = place.name
            }
            null -> return
        }
        suggestions = emptyList()
        active = null
        focus.clearFocus()
        quote()
    }

    fun resetPickup() {
        scope.launch {
            val place = calculatedPickup
                ?: LocationProvider.current(context)?.let { here ->
                    LocationProvider.describe(context, here).also { calculatedPickup = it }
                }
                ?: return@launch
            pickup = place
            pickupQuery = place.name
            active = null
            suggestions = emptyList()
            focus.clearFocus()
            quote()
        }
    }

    fun runSearch() {
        val field = active ?: return
        scope.launch {
            val q = (if (field == RouteField.PICKUP) pickupQuery else destinationQuery).trim()
            val current = if (field == RouteField.PICKUP) pickup else destination
            val place = current
                ?: suggestions.firstOrNull()
                ?: LocationProvider.search(context, q).firstOrNull()
            if (place != null) select(place)
        }
    }

    // A trip handed in from the maps route sheet via the taxi://book deep link: pre-fill both
    // endpoints and immediately compare fares. Consumed once, then cleared.
    LaunchedEffect(bookingTrip?.value) {
        val state = bookingTrip ?: return@LaunchedEffect
        val trip = state.value ?: return@LaunchedEffect
        pickup = trip.pickup
        pickupQuery = trip.pickup.name
        destination = trip.destination
        destinationQuery = trip.destination.name
        suggestions = emptyList()
        active = null
        state.value = null
        quote()
    }

    AppScaffold(title = stringResource(R.string.nav_ride)) { padding ->
        val contentData = RideContentData(
            camera = camera, routeOverlay = routeOverlay,
            pickup = pickup, destination = destination,
            pickupQuery = pickupQuery, destinationQuery = destinationQuery,
            suggestions = suggestions, searching = searching, active = active,
            results = results, comparing = comparing,
        )
        val contentEvents = RideContentEvents(
            onPickupChange = {
                pickupQuery = it
                active = RouteField.PICKUP
                if (it != pickup?.name) {
                    pickup = null
                    results = emptyMap()
                }
            },
            onPickupFocus = { active = RouteField.PICKUP },
            onResetPickup = { resetPickup() },
            onDestinationChange = {
                destinationQuery = it
                active = RouteField.DESTINATION
                if (it != destination?.name) {
                    destination = null
                    results = emptyMap()
                }
            },
            onDestinationFocus = { active = RouteField.DESTINATION },
            onClearDestination = {
                destinationQuery = ""
                destination = null
                suggestions = emptyList()
                results = emptyMap()
                active = RouteField.DESTINATION
            },
            onSearch = { runSearch() },
            onSelect = { select(it) },
            onBook = { provider, quote ->
                val from = pickup
                val to = destination
                if (from != null && to != null) {
                    // A signed-in Lyft fare (has an offer id) books in-app; everything else —
                    // deep-links to the provider as before.
                    if (provider == Provider.LYFT && quote?.offerId != null) {
                        val session = (results[Provider.LYFT] as? QuoteResult.Success)?.purchaseSessionId
                        booking = LyftBookingRequest(quote, from, to, session)
                    } else {
                        RideDeepLinks.openBooking(context, provider, from, to, quote)
                    }
                }
            },
        )
        if (isExpandedWidth()) {
            RideWideContent(
                data = contentData,
                events = contentEvents,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            RideNarrowContent(
                data = contentData,
                events = contentEvents,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }

        booking?.let { request ->
            LyftBookingFlow(request = request, onDismiss = { booking = null })
        }
    }
}
