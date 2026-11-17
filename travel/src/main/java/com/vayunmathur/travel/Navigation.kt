package com.vayunmathur.travel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.OpenSettingsIfRequested
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.ui.AncillariesPage
import com.vayunmathur.travel.ui.CancellationPage
import com.vayunmathur.travel.ui.ChangePage
import com.vayunmathur.travel.ui.ConfirmationPage
import com.vayunmathur.travel.ui.FareSelectPage
import com.vayunmathur.travel.ui.FlightResultsPage
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

@Composable
fun Navigation(viewModel: TravelViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.FlightsHome)
    // Land on settings when opened from the system App Info page.
    backStack.OpenSettingsIfRequested(Route.Settings)
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
