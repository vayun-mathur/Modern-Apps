package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.AirportCatalog
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.network.TravelRaw
import com.vayunmathur.travel.network.travelTransport
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.initPassengers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Identity fields, autocomplete suggestions, partial outbound flow, stay guests form. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelDeep2Test {

    @get:Rule val rule = createComposeRule()

    private class FakeRepo(ctx: android.content.Context) : TravelRepository(ctx) {
        val trips = MutableStateFlow<List<BookedTrip>>(emptyList())
        override fun observeRecent(limit: Int): Flow<List<RecentSearch>> = MutableStateFlow(emptyList())
        override fun observeBookedTrips(): Flow<List<BookedTrip>> = trips
        override fun observeFrequentFlyers(): Flow<List<FrequentFlyer>> = MutableStateFlow(emptyList())
        override fun observeCustomers(): Flow<List<Customer>> = MutableStateFlow(emptyList())
        override suspend fun getAllFrequentFlyers(): List<FrequentFlyer> = emptyList()
        override suspend fun getBookedTrip(orderId: String): BookedTrip? = trips.value.find { it.orderId == orderId }
        override suspend fun insertRecent(value: RecentSearch) {}
        override suspend fun clearRecent() {}
        override suspend fun trimRecent(keep: Int) {}
        override suspend fun upsertBookedTrip(value: BookedTrip) {}
    }

    private lateinit var repo: FakeRepo
    @BeforeTest fun open() {
        repo = FakeRepo(ApplicationProvider.getApplicationContext())
        val slice = """{"id":"sl_1","origin":"SFO","destination":"JFK","departureAt":"2027-06-24T07:15:00","arrivalAt":"2027-06-24T15:50:00","segments":[{"carrierIata":"AS","flightNumber":"100","origin":"SFO","destination":"JFK","departureAt":"2027-06-24T07:15:00","arrivalAt":"2027-06-24T15:50:00"}]}"""
        val ret = """{"id":"sl_2","origin":"JFK","destination":"SFO","departureAt":"2027-06-26T07:15:00","arrivalAt":"2027-06-26T15:50:00","segments":[{"carrierIata":"AS","flightNumber":"101","origin":"JFK","destination":"SFO","departureAt":"2027-06-26T07:15:00","arrivalAt":"2027-06-26T15:50:00"}]}"""
        val offer = """{"offerId":"off_1","totalAmount":"250.00","currency":"USD","passengerIds":["pax_1"],"passengers":[{"id":"pax_1","type":"adult"}],"slices":[$slice,$ret]}"""
        travelTransport = { _, url, _ ->
            when {
                url.contains("/flights") -> TravelRaw(200, """{"offerRequestId":"req_1","offers":[$offer]}""")
                url.contains("/stays/quote") -> TravelRaw(200, """{"id":"quote_1","totalAmount":"180.00","totalCurrency":"USD"}""")
                else -> TravelRaw(404, """{"message":"not found"}""")
            }
        }
    }
    @AfterTest fun clear() { travelTransport = null }

    private fun vm(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, repo)
    }
    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))
    private fun offer(identity: Boolean) = OfferDto(
        offerId = "off_1", totalAmount = "250.00", passengerIdentityDocumentsRequired = identity,
        passengerIds = listOf("pax_1"), passengers = listOf(OfferPassengerDto(id = "pax_1", type = "adult")),
        slices = listOf(SliceDto(origin = "SFO", destination = "JFK", segments = listOf(SegmentDto(carrierIata = "AS")))),
    )

    @Test fun passengers_identityFields_typing() {
        val v = vm(); v.reviewMutable.value = com.vayunmathur.travel.util.OfferReviewState(offer = offer(identity = true)); v.initPassengers(offer(identity = true))
        rule.setContent { PassengersPage(stack(), v, Route.Passengers("off_1")) }
        rule.onNodeWithText("Given name").performTextInput("Jane")
        rule.onAllNodesWithText("Family name")[0].performScrollTo()
        rule.onAllNodesWithText("Date of birth")[0].performScrollTo()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Passport number").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Passport number")[0].performScrollTo().performTextInput("X123456")
        rule.runOnIdle {
            assertEquals("Jane", v.passengers.value[0].givenName)
            assertEquals("X123456", v.passengers.value[0].identityDocument?.uniqueIdentifier)
        }
    }

    @Test fun flightSearch_autocompleteSuggestion() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        AirportCatalog.ensureLoaded(ctx)
        val v = vm()
        rule.setContent { FlightSearchForm(v, FlightFormState(tripType = TripType.ONE_WAY), {}, onSearch = {}) }
        rule.onNodeWithText("From").performTextInput("New York")
        rule.waitUntil(5000) { rule.onAllNodesWithText("JFK", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("JFK", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun outboundSelect_withTransportOffers() {
        val v = vm()
        rule.setContent { OutboundSelectPage(stack(), v, Route.OutboundSelect("SFO:JFK:2027-06-24,JFK:SFO:2027-06-26", 1, "", 0, "economy", 2)) }
        rule.waitUntil(5000) { v.partialFlow.value.allOffers.isNotEmpty() }
        rule.onNodeWithText("Choose flights").assertIsDisplayed()
        rule.onAllNodesWithText("SFO", substring = true)[0].assertExists()
    }

    @Test fun stayGuests_formFields() {
        rule.setContent { StayGuestsPage(stack(), vm()) }
        rule.onNodeWithText("Guest details").assertIsDisplayed()
        rule.onNodeWithText("Given name").performTextInput("Jane")
        rule.onNodeWithText("Family name").assertExists()
    }
}
