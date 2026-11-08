package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.util.TravelViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** VM-backed pages rendered with an empty fake repository: scaffold titles and empty states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelPagesRenderTest {

    @get:Rule val rule = createComposeRule()

    private class FakeRepo(ctx: android.content.Context) : TravelRepository(ctx) {
        val trips = MutableStateFlow<List<BookedTrip>>(emptyList())
        override fun observeRecent(limit: Int): Flow<List<RecentSearch>> = MutableStateFlow(emptyList())
        override fun observeBookedTrips(): Flow<List<BookedTrip>> = trips
        override fun observeFrequentFlyers(): Flow<List<FrequentFlyer>> = MutableStateFlow(emptyList())
        override fun observeCustomers(): Flow<List<Customer>> = MutableStateFlow(emptyList())
        override suspend fun getAllFrequentFlyers(): List<FrequentFlyer> = emptyList()
        override suspend fun insertRecent(value: RecentSearch) {}
        override suspend fun clearRecent() {}
        override suspend fun trimRecent(keep: Int) {}
        override suspend fun upsertBookedTrip(value: BookedTrip) {}
        override suspend fun getBookedTrip(orderId: String): BookedTrip? = trips.value.find { it.orderId == orderId }
    }

    private lateinit var repo: FakeRepo
    @BeforeTest fun open() {
        repo = FakeRepo(ApplicationProvider.getApplicationContext())
    }
    private fun vm(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, repo)
    }
    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    @Test fun home_showsFlightsAndStays() {
        rule.setContent { HomePage(stack(), vm()) }
        rule.onNodeWithText("Flights").assertIsDisplayed()
        rule.onNodeWithText("Stays").performClick()
        rule.onNodeWithText("Stays").assertIsDisplayed()
    }

    @Test fun settings_showsTitle() {
        rule.setContent { SettingsPage(stack(), vm()) }
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun trips_emptyShowsTitle() {
        rule.setContent { TripsPage(stack(), vm()) }
        rule.onNodeWithText("My trips").assertIsDisplayed()
    }

    @Test fun passengers_showsTitle() {
        rule.setContent { PassengersPage(stack(), vm(), Route.Passengers("off_1")) }
        rule.onNodeWithText("Passengers").assertIsDisplayed()
    }

    @Test fun offerReview_showsTitle() {
        rule.setContent { OfferReviewPage(stack(), vm(), Route.OfferReview("off_1")) }
        rule.onNodeWithText("Review flight").assertIsDisplayed()
    }

    @Test fun ancillaries_showsTitle() {
        rule.setContent { AncillariesPage(stack(), vm(), Route.Ancillaries("off_1")) }
        rule.onNodeWithText("Add extras").assertIsDisplayed()
    }

    @Test fun payment_showsTitle() {
        rule.setContent { PaymentPage(stack(), vm(), Route.Payment("off_1")) }
        rule.onNodeWithText("Payment").assertIsDisplayed()
    }

    @Test fun confirmation_unknownTrip() {
        rule.setContent { ConfirmationPage(stack(), vm(), Route.Confirmation("ord_missing")) }
        rule.onNodeWithText("Trip not found.").assertIsDisplayed()
    }

    @Test fun change_showsTitle() {
        rule.setContent { ChangePage(stack(), vm(), Route.Change("ord_1")) }
        rule.onNodeWithText("Change flights").assertIsDisplayed()
    }

    @Test fun cancellation_showsTitle() {
        rule.setContent { CancellationPage(stack(), vm(), Route.Cancel("ord_1")) }
        rule.onNodeWithText("Cancel order").assertIsDisplayed()
    }

    @Test fun stayGuests_showsTitle() {
        rule.setContent { StayGuestsPage(stack(), vm()) }
        rule.onNodeWithText("Guest details").assertIsDisplayed()
    }

    @Test fun stayDetail_showsHotelFallback() {
        rule.setContent { StayDetailPage(stack(), vm(), Route.StayDetail("sr_1", "")) }
        rule.onNodeWithText("Hotel").assertIsDisplayed()
    }

    @Test fun stayConfirmation_renders() {
        rule.setContent { StayConfirmationPage(stack(), vm(), Route.StayConfirmation("bk_1")) }
        rule.onNodeWithText("Booking confirmed").assertIsDisplayed()
        rule.onNodeWithText("You're booked!").assertIsDisplayed()
    }

    @Test fun flightSearchForm_renders() {
        rule.setContent { FlightSearchForm(vm(), FlightFormState(), {}, onSearch = {}) }
        rule.onNodeWithText("Search flights").assertIsDisplayed()
    }

    @Test fun flightResults_renders() {
        rule.setContent { FlightResultsPage(stack(), vm(), Route.FlightResults("SFO:JFK", 1, "", 0, "economy", 2)) }
        rule.onNodeWithText("SFO → JFK").assertIsDisplayed()
    }

    @Test fun outboundSelect_showsTitle() {
        rule.setContent { OutboundSelectPage(stack(), vm(), Route.OutboundSelect("SFO-JFK", 1, "", 0, "economy", 2)) }
        rule.onNodeWithText("Choose flights").assertIsDisplayed()
    }

    @Test fun fareSelect_showsTitle() {
        rule.setContent { FareSelectPage(stack(), vm(), Route.FareSelect("sig1", "sig2")) }
        rule.onNodeWithText("Choose fare").assertIsDisplayed()
    }

    @Test fun stayResults_renders() {
        rule.setContent { StayResultsPage(stack(), vm(), Route.StayResults("Paris", "2026-06-24", "2026-06-26", 1, 2)) }
        rule.onAllNodesWithText("Paris", substring = true)[0].assertExists()
    }

    @Test fun staySearchForm_renders() {
        rule.setContent {
            StaySearchForm(vm(), StayFormState(), {}, onSearch = { _, _, _, _, _, _, _ -> })
        }
        rule.onNodeWithText("Search hotels").assertIsDisplayed()
    }
}
