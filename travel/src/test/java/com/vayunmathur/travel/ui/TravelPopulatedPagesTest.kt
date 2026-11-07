package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.ServiceDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.network.TripAncillariesDto
import com.vayunmathur.travel.util.AncillariesState
import com.vayunmathur.travel.util.OfferReviewState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.initPassengers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Populated VM states: passenger forms, offer review, ancillaries, trips, settings lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelPopulatedPagesTest {

    @get:Rule val rule = createComposeRule()

    private class FakeRepo(ctx: android.content.Context) : TravelRepository(ctx) {
        val trips = MutableStateFlow<List<BookedTrip>>(emptyList())
        val customers = MutableStateFlow<List<Customer>>(emptyList())
        val flyers = MutableStateFlow<List<FrequentFlyer>>(emptyList())
        val recents = MutableStateFlow<List<RecentSearch>>(emptyList())
        override fun observeRecent(limit: Int): Flow<List<RecentSearch>> = recents
        override fun observeBookedTrips(): Flow<List<BookedTrip>> = trips
        override fun observeFrequentFlyers(): Flow<List<FrequentFlyer>> = flyers
        override fun observeCustomers(): Flow<List<Customer>> = customers
        override suspend fun getAllFrequentFlyers(): List<FrequentFlyer> = flyers.value
        override suspend fun getBookedTrip(orderId: String): BookedTrip? = trips.value.find { it.orderId == orderId }
    }

    private lateinit var repo: FakeRepo
    @BeforeTest fun open() { repo = FakeRepo(ApplicationProvider.getApplicationContext()) }
    private fun vm(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, repo)
    }
    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    private fun offer() = OfferDto(
        offerId = "off_1", itineraryId = "itin_1", totalAmount = "250.00", currency = "USD",
        owner = "Alaska", ownerIata = "AS", passengerIds = listOf("pax_1"),
        passengers = listOf(OfferPassengerDto(id = "pax_1", type = "adult")),
        refundPolicy = "Refundable", changePolicy = "Changes allowed",
        slices = listOf(SliceDto(
            origin = "SFO", destination = "JFK",
            departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
            durationMinutes = 315, segments = listOf(SegmentDto(carrierIata = "AS", flightNumber = "100", origin = "SFO", destination = "JFK", departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00")),
        )),
    )

    private fun trip() = BookedTrip(
        orderId = "ord_1", bookingReference = "ABC123", route = "SFO → JFK",
        departDate = "2026-06-24", amount = "250.00", currency = "USD", status = "confirmed",
    )

    @Test fun passengers_withSeededPassenger_showsForm() {
        val v = vm(); v.initPassengers(offer())
        rule.setContent { PassengersPage(stack(), v, Route.Passengers("off_1")) }
        rule.onNodeWithText("Passengers").assertIsDisplayed()
        rule.onNodeWithText("Given name").assertIsDisplayed()
    }

    @Test fun offerReview_withOffer_showsPrice() {
        val v = vm(); v.reviewMutable.value = OfferReviewState(offer = offer())
        rule.setContent { OfferReviewPage(stack(), v, Route.OfferReview("off_1")) }
        rule.onNodeWithText("Review flight").assertIsDisplayed()
        rule.onNodeWithText("$250").assertExists()
    }

    @Test fun ancillaries_withServices_showsRows() {
        val v = vm()
        v.reviewMutable.value = OfferReviewState(offer = offer())
        v.ancillariesMutable.value = AncillariesState(ancillaries = TripAncillariesDto(
            status = "ok",
            services = listOf(
                ServiceDto(id = "bag_1", type = "baggage", title = "Checked bag", totalAmount = "30.00", maxQuantity = 3),
                ServiceDto(id = "ext_1", type = "extra", title = "Priority boarding", totalAmount = "15.00", maxQuantity = 1),
            ),
        ))
        rule.setContent { AncillariesPage(stack(), v, Route.Ancillaries("off_1")) }
        rule.onNodeWithText("Checked bag").assertIsDisplayed()
        rule.onNodeWithText("Priority boarding").assertIsDisplayed()
    }

    @Test fun trips_withTrip_showsRoute() {
        repo.trips.value = listOf(trip())
        rule.setContent { TripsPage(stack(), vm()) }
        rule.onNodeWithText("SFO → JFK").assertIsDisplayed()
        rule.onNodeWithText("ABC123", substring = true).assertExists()
    }

    @Test fun confirmation_withTrip_showsReference() {
        repo.trips.value = listOf(trip())
        rule.setContent { ConfirmationPage(stack(), vm(), Route.Confirmation("ord_1")) }
        rule.onNodeWithText("ABC123").assertIsDisplayed()
    }

    @Test fun home_withRecentsAndTrips_showsCards() {
        repo.trips.value = listOf(trip())
        repo.recents.value = listOf(RecentSearch(vertical = "flights", label = "SFO → JFK"))
        rule.setContent { HomePage(stack(), vm()) }
        rule.onNodeWithText("Recent searches").assertExists()
    }

    @Test fun settings_withCustomerAndFlyer_showsRows() {
        repo.customers.value = listOf(Customer(id = "c1", email = "a@b.c", givenName = "Jane", familyName = "Doe"))
        repo.flyers.value = listOf(FrequentFlyer(airlineIata = "AS", accountNumber = "12345", airlineName = "Alaska"))
        rule.setContent { SettingsPage(stack(), vm()) }
        rule.onNodeWithText("Jane Doe").assertExists()
    }

    @Test fun payment_withReview_showsTotal() {
        val v = vm(); v.reviewMutable.value = OfferReviewState(offer = offer())
        rule.setContent { PaymentPage(stack(), v, Route.Payment("off_1")) }
        rule.onNodeWithText("Payment").assertIsDisplayed()
    }
}
