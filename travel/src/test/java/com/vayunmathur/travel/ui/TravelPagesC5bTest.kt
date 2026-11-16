package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.AirlineDto
import com.vayunmathur.travel.network.BaggageDto
import com.vayunmathur.travel.network.ConditionsDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.PenaltyRuleDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.ServiceDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.network.TripAncillariesDto
import com.vayunmathur.travel.util.AncillariesState
import com.vayunmathur.travel.util.BookingState
import com.vayunmathur.travel.util.OfferReviewState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.initPassengers
import com.vayunmathur.travel.util.prefillFromProfile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 page branches, part 2: mixed passenger types with identity documents,
 * multi-passenger ancillaries with seat choices, and a round-trip offer
 * review with policies and operating-carrier disclosure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelPagesC5bTest {

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
        override suspend fun insertRecent(value: RecentSearch) {}
        override suspend fun clearRecent() {}
        override suspend fun trimRecent(keep: Int) {}
        override suspend fun upsertBookedTrip(value: BookedTrip) {}
    }

    private lateinit var repo: FakeRepo

    @BeforeTest
    fun open() {
        repo = FakeRepo(ApplicationProvider.getApplicationContext())
        com.vayunmathur.travel.network.travelTransport = null
    }

    private fun vm(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, repo)
    }

    private fun stack() = NavBackStack<Route>(arrayOf(Route.FlightsHome))

    private fun segment(id: String, origin: String, destination: String) = SegmentDto(
        id = id, carrier = "Alaska Airlines", carrierIata = "AS", flightNumber = "100",
        origin = origin, destination = destination,
        departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00",
        aircraft = "737-900", cabinClass = "Economy",
        baggages = listOf(BaggageDto(type = "checked", quantity = 1)),
        operatingCarrierIata = "OO",
    )

    private fun offer() = OfferDto(
        offerId = "off_1", totalAmount = "250.00", currency = "USD",
        owner = "Alaska", ownerIata = "AS",
        passengerIds = listOf("pax_1", "pax_2", "pax_3"),
        passengers = listOf(
            OfferPassengerDto(id = "pax_1", type = "adult"),
            OfferPassengerDto(id = "pax_2", type = "child", age = 7),
            OfferPassengerDto(id = "pax_3", type = "infant_without_seat"),
        ),
        passengerIdentityDocumentsRequired = true,
        refundPolicy = "Refundable before departure", changePolicy = "Changes for a fee",
        fareBrand = "Main Cabin",
        conditions = ConditionsDto(
            refundBeforeDeparture = PenaltyRuleDto(allowed = true),
            changeBeforeDeparture = PenaltyRuleDto(allowed = true),
        ),
        slices = listOf(
            SliceDto(
                origin = "SFO", destination = "JFK", durationMinutes = 315, stops = 0,
                departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00",
                segments = listOf(segment("seg_1", "SFO", "JFK")),
            ),
            SliceDto(
                origin = "JFK", destination = "SFO", durationMinutes = 330, stops = 0,
                departureAt = "2027-06-28T09:00:00", arrivalAt = "2027-06-28T12:30:00",
                segments = listOf(segment("seg_2", "JFK", "SFO")),
            ),
        ),
    )

    @Test
    fun passengers_mixedTypes_identityAndMissingDetails() {
        val v = vm()
        repo.customers.value = listOf(
            Customer(id = "c1", email = "jane@example.com", givenName = "Jane", familyName = "Doe"),
        )
        v.reviewMutable.value = OfferReviewState(offer = offer())
        v.initPassengers(offer())
        v.bookingMutable.value = BookingState.MissingDetails(
            listOf("passport"), "Passport details are required",
        )
        v.airlinesMutable.value = listOf(AirlineDto(iataCode = "AS", name = "Alaska Airlines"))
        rule.setContent { PassengersPage(stack(), v, Route.Passengers("off_1")) }
        rule.onNodeWithText("Passport details are required").assertIsDisplayed()
        rule.onNodeWithText("Adult 1").performScrollTo().assertExists()
        rule.onNodeWithText("Child (7) 2").performScrollTo().assertExists()
        // "Infant 3" appears twice: the passenger heading and the adult's infant-link chip.
        rule.onAllNodesWithText("Infant 3")[1].performScrollTo().assertExists()
        rule.onAllNodesWithText("Passport number")[0].performScrollTo().assertExists()
        // The saved-profile chip is offered; tapping it prefills via the VM
        // (asserted directly in TravelBookingC5Test, chip wiring in TravelFieldsTest).
        rule.onAllNodesWithText("Jane Doe")[0].performScrollTo().assertExists()
        rule.runOnIdle {
            v.prefillFromProfile("jane@example.com", "Jane", "Doe", "555-1")
            assertEquals("jane@example.com", v.passengers.value[0].email)
            assertEquals("Jane", v.passengers.value[0].givenName)
        }
        // Booking error branch replaces the missing-details message.
        rule.runOnIdle { v.bookingMutable.value = BookingState.Error("Trip creation failed") }
        rule.onNodeWithText("Trip creation failed").performScrollTo().assertExists()
        rule.runOnIdle { v.bookingMutable.value = BookingState.Loading }
        rule.onNodeWithText("Creating trip…").performScrollTo().assertExists()
    }

    @Test
    fun ancillaries_multiPassenger_seatsAndSummary() {
        val v = vm()
        v.reviewMutable.value = OfferReviewState(offer = offer())
        v.ancillariesMutable.value = AncillariesState(
            ancillaries = TripAncillariesDto(
                services = listOf(
                    ServiceDto(id = "bag_1", type = "baggage", title = "Checked bag", totalAmount = "30.00", totalCurrency = "USD", maxQuantity = 3, passengerIds = listOf("pax_2")),
                    ServiceDto(id = "ext_1", type = "extra", title = "Priority boarding", totalAmount = "15.00", totalCurrency = "USD", maxQuantity = 1, passengerIds = listOf("pax_1")),
                    ServiceDto(id = "seat_1", type = "seat", title = "Seat", totalAmount = "25.00", totalCurrency = "USD", passengerIds = listOf("pax_1")),
                ),
            ),
        )
        v.selectedBaggageMutable.value = mapOf("bag_1" to 2L)
        v.selectedExtrasMutable.value = mapOf("ext_1" to 1L)
        v.selectedSeatsMutable.value = mapOf(
            "seg_1|pax_1" to com.vayunmathur.travel.network.SeatElementDto(
                type = "seat", designator = "14A", available = true, serviceId = "seat_1", totalAmount = "25.00",
            ),
        )
        rule.setContent { AncillariesPage(stack(), v, Route.Ancillaries("off_1")) }
        rule.onNodeWithText("Checked bag").assertIsDisplayed()
        rule.onNodeWithText("Priority boarding").performScrollTo().assertExists()
        rule.onAllNodesWithText("Child 2", substring = true)[0].performScrollTo().assertExists()
        rule.onAllNodesWithText("14A", substring = true)[0].performScrollTo().assertExists()
        rule.onNodeWithText("Continue to checkout").performScrollTo().assertExists()
        // Error branch.
        rule.runOnIdle {
            v.ancillariesMutable.value = v.ancillaries.value.copy(error = "Extras failed to save")
        }
        rule.onNodeWithText("Extras failed to save").performScrollTo().assertExists()
    }

    @Test
    fun ancillaries_seatMapsPending_showsRetryCard() {
        val v = vm()
        v.reviewMutable.value = OfferReviewState(offer = offer())
        v.ancillariesMutable.value = AncillariesState(
            ancillaries = TripAncillariesDto(services = emptyList()),
            seatMapsLoading = true,
        )
        rule.setContent { AncillariesPage(stack(), v, Route.Ancillaries("off_1")) }
        rule.onNodeWithText("Seat maps are still loading", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Retry").performScrollTo().assertExists()
    }

    @Test
    fun ancillaries_noOffer_showsEmptyState() {
        val v = vm()
        rule.setContent { AncillariesPage(stack(), v, Route.Ancillaries("off_1")) }
        rule.onNodeWithText("No offer selected.").assertIsDisplayed()
    }

    @Test
    fun offerReview_roundTrip_policiesAndOperatingCarrier() {
        val v = vm()
        v.reviewMutable.value = OfferReviewState(offer = offer())
        val s = stack()
        rule.setContent { OfferReviewPage(s, v, Route.OfferReview("off_1")) }
        rule.onNodeWithText("Alaska").assertIsDisplayed()
        rule.onNodeWithText("Main Cabin").performScrollTo().assertExists()
        rule.onNodeWithText("Refundable").performScrollTo().assertExists()
        rule.onNodeWithText("Outbound").performScrollTo().assertExists()
        rule.onNodeWithText("Return").performScrollTo().assertExists()
        rule.onAllNodesWithText("737-900", substring = true)[0].performScrollTo().assertExists()
        rule.onAllNodesWithText("Operated by", substring = true)[0].performScrollTo().assertExists()
        rule.onNodeWithText("Fare policies").performScrollTo().assertExists()
        rule.onAllNodesWithText("$250", substring = true)[0].performScrollTo().assertExists()
        assertTrue(s.backStack.last() is Route.FlightsHome)
    }
}
