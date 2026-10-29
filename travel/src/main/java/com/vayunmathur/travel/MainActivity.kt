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
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.ui.AncillariesPage
import com.vayunmathur.travel.ui.CancellationPage
import com.vayunmathur.travel.ui.ChangePage
import com.vayunmathur.travel.ui.ConfirmationPage
import com.vayunmathur.travel.ui.ExploreMapPage
import com.vayunmathur.travel.ui.FlightResultsPage
import com.vayunmathur.travel.ui.FareSelectPage
import com.vayunmathur.travel.ui.HomePage
import com.vayunmathur.travel.ui.OfferReviewPage
import com.vayunmathur.travel.ui.OrderDetailPage
import com.vayunmathur.travel.ui.OutboundSelectPage
import com.vayunmathur.travel.ui.PassengersPage
import com.vayunmathur.travel.ui.PaymentPage
import com.vayunmathur.travel.ui.ReturnSelectPage
import com.vayunmathur.travel.ui.SeatMapPage
import com.vayunmathur.travel.ui.SettingsPage
import com.vayunmathur.travel.ui.StayConfirmationPage
import com.vayunmathur.travel.ui.StayDetailPage
import com.vayunmathur.travel.ui.StayGuestsPage
import com.vayunmathur.travel.ui.StayResultsPage
import com.vayunmathur.travel.ui.TripsPage
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.TravelViewModelFactory
import com.vayunmathur.travel.util.pendingTripIds
import com.vayunmathur.travel.util.pollTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.serialization.Serializable

class MainActivity : MaActivity() {
    private lateinit var repository: TravelRepository

    private val viewModel: TravelViewModel by viewModels {
        TravelViewModelFactory(application, repository)
    }
    private var ready by mutableStateOf(false)

    override fun onCreateBeforeContent() {
        // Hardened trust: api.maproj.org + data.maproj.org are Cloudflare
        // fronted, covered by ISRG X1/X2 + GTS R1-R4 in FIRST_PARTY bundle.
        // (findfamily.cc relay migrated to api.maproj.org — same pins.)
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)

        lifecycleScope.launch(Dispatchers.IO) {
            repository = TravelRepository.get(this@MainActivity)
            withContext(Dispatchers.Main) { ready = true }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the hosted-checkout Custom Tab: resume polling any trip
        // that is still awaiting payment / processing.
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

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    @Serializable
    data class FlightResults(
        val slices: String,
        val adults: Int,
        val children: String,
        val infants: Int,
        val cabin: String,
        val maxConnections: Int,
    ) : Route

    /**
     * Explore map: search params only (never the destination list — the page
     * fetches via the ViewModel). [tripType] is `oneway` or `roundtrip`.
     */
    @Serializable
    data class Explore(
        val origin: String,
        val tripType: String,
        val depart: String,
        val returnDate: String? = null,
        val cabin: String = "economy",
        val adults: Int = 1,
        val children: String = "",
        val infants: Int = 0,
        val maxConnections: Int = -1,
    ) : Route

    @Serializable
    data class OfferReview(val offerId: String) : Route

    @Serializable
    data class OutboundSelect(
        val slices: String,
        val adults: Int,
        val children: String,
        val infants: Int,
        val cabin: String,
        val maxConnections: Int,
    ) : Route

    @Serializable
    data class ReturnSelect(val outboundSignature: String) : Route

    @Serializable
    data class FareSelect(val outboundSignature: String, val inboundSignature: String) : Route

    @Serializable
    data class Ancillaries(val offerId: String) : Route

    @Serializable
    data class SeatMap(val offerId: String, val segmentId: String) : Route

    @Serializable
    data class Passengers(val offerId: String) : Route

    @Serializable
    data class Payment(val offerId: String) : Route

    @Serializable
    data class Confirmation(val orderId: String) : Route

    @Serializable
    data class OrderDetail(val orderId: String) : Route

    @Serializable
    data class Cancel(val orderId: String) : Route

    @Serializable
    data class Change(val orderId: String) : Route

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

    @Serializable
    data class StayDetail(val searchResultId: String, val name: String) : Route

    @Serializable
    data object StayGuests : Route

    @Serializable
    data class StayConfirmation(val bookingId: String) : Route

    @Serializable
    data object Trips : Route

    @Serializable
    data object Settings : Route
}

@Composable
fun MainGraph(viewModel: TravelViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> { HomePage(backStack, viewModel) }
            entry<Route.FlightResults>(metadata = ListPage()) { FlightResultsPage(backStack, viewModel, it) }
            entry<Route.Explore> { ExploreMapPage(backStack, viewModel, it) }
            entry<Route.OutboundSelect> { OutboundSelectPage(backStack, viewModel, it) }
            entry<Route.ReturnSelect> { ReturnSelectPage(backStack, viewModel, it) }
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
