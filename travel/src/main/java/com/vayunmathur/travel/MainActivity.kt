package com.vayunmathur.travel

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.ui.AncillariesPage
import com.vayunmathur.travel.ui.CancellationPage
import com.vayunmathur.travel.ui.ChangePage
import com.vayunmathur.travel.ui.ConfirmationPage
import com.vayunmathur.travel.ui.FlightResultsPage
import com.vayunmathur.travel.ui.FareSelectPage
import com.vayunmathur.travel.ui.HomePage
import com.vayunmathur.travel.ui.OfferReviewPage
import com.vayunmathur.travel.ui.OrderDetailPage
import com.vayunmathur.travel.ui.OutboundSelectPage
import com.vayunmathur.travel.ui.PassengersPage
import com.vayunmathur.travel.ui.PaymentPage
import com.vayunmathur.travel.ui.SeatMapPage
import com.vayunmathur.travel.ui.SettingsPage
import com.vayunmathur.travel.ui.StayConfirmationPage
import com.vayunmathur.travel.ui.StayDetailPage
import com.vayunmathur.travel.ui.StayGuestsPage
import com.vayunmathur.travel.ui.StayResultsPage
import com.vayunmathur.travel.ui.TripsPage
import com.vayunmathur.travel.data.RemoteImageFetchers
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.TravelViewModelFactory
import com.vayunmathur.travel.util.pendingTripIds
import com.vayunmathur.travel.util.pollTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.serialization.Serializable

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private lateinit var repository: TravelRepository

    private val viewModel: TravelViewModel by viewModels {
        TravelViewModelFactory(application, repository)
    }
    private var ready by mutableStateOf(false)

    override fun onCreateBeforeContent() {
        // Hardened trust: api.maproj.org + data.maproj.org are Cloudflare
        // fronted, covered by ISRG X1/X2 + GTS R1-R4 in FIRST_PARTY bundle.
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        // Destination and stay photos load over HTTP(S) through the shared image
        // pipeline; the fetch function is built here from NetworkClient so no
        // library-to-library edge is needed (#884).
        ImageLoader.setDefault(
            ImageLoader.Builder(this).remote(RemoteImageFetchers::network).build(),
        )

        lifecycleScope.launch(Dispatchers.IO) {
            repository = TravelRepository.get(this@MainActivity)
            withContext(Dispatchers.Main) { ready = true }
        }
    }

    override fun onResume() {
        super.onResume()
        // Resume polling any trip that is still awaiting payment / processing.
        if (!::repository.isInitialized) return
        lifecycleScope.launch {
            viewModel.pendingTripIds().forEach { viewModel.pollTrip(it) }
        }
    }

    @Composable
    override fun Content() {
        if (ready) MainGraph(viewModel)
    }
}

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding flights-home state and operations. */
    @Serializable
    data object FlightsHome : Route

    /** Singleton holding stays-home state and operations. */
    @Serializable
    data object StaysHome : Route

    /** Immutable value describing flight results. */
    @Serializable
    data class FlightResults(
        val slices: String,
        val adults: Int,
        val children: String,
        val infants: Int,
        val cabin: String,
        val maxConnections: Int,
    ) : Route

    /** Immutable value describing offer review; carries offerId. */
    @Serializable
    data class OfferReview(val offerId: String) : Route

    /** Immutable value describing outbound select. */
    @Serializable
    data class OutboundSelect(
        val slices: String,
        val adults: Int,
        val children: String,
        val infants: Int,
        val cabin: String,
        val maxConnections: Int,
    ) : Route


    /** Immutable value describing fare select; carries outboundSignature, inboundSignature. */
    @Serializable
    data class FareSelect(val outboundSignature: String, val inboundSignature: String) : Route

    /** Immutable value describing ancillaries; carries offerId. */
    @Serializable
    data class Ancillaries(val offerId: String) : Route

    /** Immutable value describing seat map; carries offerId, segmentId. */
    @Serializable
    data class SeatMap(val offerId: String, val segmentId: String) : Route

    /** Immutable value describing passengers; carries offerId. */
    @Serializable
    data class Passengers(val offerId: String) : Route

    /** Immutable value describing payment; carries offerId. */
    @Serializable
    data class Payment(val offerId: String) : Route

    /** Immutable value describing confirmation; carries orderId. */
    @Serializable
    data class Confirmation(val orderId: String) : Route

    /** Immutable value describing order detail; carries orderId. */
    @Serializable
    data class OrderDetail(val orderId: String) : Route

    /** Immutable value describing cancel; carries orderId. */
    @Serializable
    data class Cancel(val orderId: String) : Route

    /** Immutable value describing change; carries orderId. */
    @Serializable
    data class Change(val orderId: String) : Route

    /** Immutable value describing stay results. */
    @Serializable
    data class StayResults(
        val place: String,
        val checkIn: String,
        val checkOut: String,
        val rooms: Int,
        val adults: Int,
        val latitude: Double = Double.NaN,
        val longitude: Double = Double.NaN,
    ) : Route

    /** Immutable value describing stay detail; carries searchResultId, name. */
    @Serializable
    data class StayDetail(val searchResultId: String, val name: String) : Route

    /** Singleton holding stay guests state and operations. */
    @Serializable
    data object StayGuests : Route

    /** Immutable value describing stay confirmation; carries bookingId. */
    @Serializable
    data class StayConfirmation(val bookingId: String) : Route

    /** Singleton holding trips state and operations. */
    @Serializable
    data object Trips : Route

    /** Singleton holding settings state and operations. */
    @Serializable
    data object Settings : Route
}

@Composable
fun MainGraph(viewModel: TravelViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.FlightsHome)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.FlightsHome>(metadata = SiblingPage()) { HomePage(backStack, viewModel, it) }
            entry<Route.StaysHome>(metadata = SiblingPage()) { HomePage(backStack, viewModel, it) }
            entry<Route.FlightResults>(metadata = ListPage()) { FlightResultsPage(backStack, viewModel, it) }
            entry<Route.OutboundSelect> { OutboundSelectPage(backStack, viewModel, it) }
            entry<Route.FareSelect> { FareSelectPage(backStack, viewModel, it) }
            entry<Route.OfferReview>(metadata = ListDetailPage() + MorphPage()) {
                OfferReviewPage(backStack, viewModel, it)
            }
            entry<Route.Ancillaries> { AncillariesPage(backStack, viewModel, it) }
            entry<Route.SeatMap> { SeatMapPage(backStack, viewModel, it) }
            entry<Route.Passengers> { PassengersPage(backStack, viewModel, it) }
            entry<Route.Payment> { PaymentPage(backStack, viewModel, it) }
            entry<Route.Confirmation> { ConfirmationPage(backStack, viewModel, it) }
            entry<Route.OrderDetail>(metadata = ListDetailPage()) { OrderDetailPage(backStack, viewModel, it) }
            entry<Route.Cancel> { CancellationPage(backStack, viewModel, it) }
            entry<Route.Change> { ChangePage(backStack, viewModel, it) }
            entry<Route.StayResults> { StayResultsPage(backStack, viewModel, it) }
            entry<Route.StayDetail>(metadata = MorphPage()) { StayDetailPage(backStack, viewModel, it) }
            entry<Route.StayGuests> { StayGuestsPage(backStack, viewModel) }
            entry<Route.StayConfirmation> { StayConfirmationPage(backStack, viewModel, it) }
            entry<Route.Trips>(metadata = ListPage()) { TripsPage(backStack, viewModel) }
            entry<Route.Settings> { SettingsPage(backStack, viewModel) }
        }
    }
}
