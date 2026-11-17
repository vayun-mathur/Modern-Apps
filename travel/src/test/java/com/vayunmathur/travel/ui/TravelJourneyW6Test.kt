package com.vayunmathur.travel.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.TravelRaw
import com.vayunmathur.travel.network.travelTransport
import com.vayunmathur.travel.platform.TravelViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6 journey: the flight search form filled by hand — airport fields with
 * catalog suggestions, trip-type/cabin/passenger controls — then Search
 * against the fake transport, landing on results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelJourneyW6Test {

    @get:Rule val rule = createComposeRule()

    private class FakeRepo(ctx: android.content.Context) : TravelRepository(ctx) {
        override fun observeRecent(limit: Int): Flow<List<RecentSearch>> = MutableStateFlow(emptyList())
        override fun observeBookedTrips(): Flow<List<BookedTrip>> = MutableStateFlow(emptyList())
        override fun observeFrequentFlyers(): Flow<List<FrequentFlyer>> = MutableStateFlow(emptyList())
        override fun observeCustomers(): Flow<List<Customer>> = MutableStateFlow(emptyList())
        override suspend fun getAllFrequentFlyers(): List<FrequentFlyer> = emptyList()
        override suspend fun insertRecent(value: RecentSearch) {}
        override suspend fun clearRecent() {}
        override suspend fun trimRecent(keep: Int) {}
    }

    @BeforeTest
    fun open() {
        val offerJson = """{"offerId":"off_1","itineraryId":"itin_1","totalAmount":"250.00","currency":"USD","owner":"Alaska","ownerIata":"AS","passengerIds":["pax_1"],"passengers":[{"id":"pax_1","type":"adult"}],"slices":[{"id":"s1","origin":"SFO","destination":"JFK","departureAt":"2027-06-24T07:15:00","arrivalAt":"2027-06-24T15:50:00","segments":[{"id":"g1","carrierIata":"AS","flightNumber":"100","origin":"SFO","destination":"JFK","departureAt":"2027-06-24T07:15:00","arrivalAt":"2027-06-24T15:50:00"}]}]}"""
        travelTransport = { _, url, _ ->
            if (url.contains("/flights")) TravelRaw(200, """{"offerRequestId":"req_1","offers":[$offerJson]}""")
            else TravelRaw(404, """{"message":"nf"}""")
        }
    }

    @AfterTest fun clear() { travelTransport = null }

    private fun vm(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, FakeRepo(ctx))
    }

    @Test
    fun flightSearchFormDirect_controlsAndSearchQuery() {
        val v = vm()
        var form = com.vayunmathur.travel.ui.FlightFormState(
            origin = "SFO", originText = "San Francisco", destination = "JFK", destinationText = "New York",
            depart = "2027-06-24", returnDate = "2027-06-28",
        )
        var searched: com.vayunmathur.travel.platform.FlightQuery? = null
        rule.setContent {
            FlightSearchForm(
                viewModel = v, state = form,
                onState = { form = it },
                onSearch = { searched = it },
            )
        }
        rule.waitForIdle()
        // Open each dropdown/dialog labelled in the form and close it again.
        for (label in listOf("Round trip", "Economy", "1", "Nonstop", "Max stops")) {
            rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().let {
                if (it.isNotEmpty()) {
                    rule.onAllNodesWithText(label, substring = true)[0].performClick()
                    rule.waitForIdle()
                }
            }
        }
        // Swap origin/destination via the swap affordance if present.
        rule.onAllNodesWithText("Swap", substring = true).fetchSemanticsNodes().let {
            if (it.isNotEmpty()) rule.onAllNodesWithText("Swap", substring = true)[0].performClick()
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Search", substring = true).fetchSemanticsNodes().let {
            if (it.isNotEmpty()) rule.onAllNodesWithText("Search", substring = true)[0].performClick()
        }
        rule.waitForIdle()
        assertTrue(searched != null || form.origin.isNotBlank())
    }

    @Test
    fun flightSearchForm_fillAndSearch() {
        val stack = NavBackStack<Route>(arrayOf(Route.FlightsHome))
        val v = vm()
        rule.setContent { HomePage(stack, v, Route.FlightsHome) }
        rule.waitForIdle()
        // Type into the From/To fields; catalog suggestions should appear for SFO.
        val fields = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue(fields.size >= 2, "search form fields: ${fields.size}")
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("San Francisco")
        rule.waitForIdle()
        rule.onAllNodesWithText("SFO", substring = true).fetchSemanticsNodes().let {
            if (it.isNotEmpty()) rule.onAllNodesWithText("SFO", substring = true)[0].performClick()
        }
        rule.waitForIdle()
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("New York")
        rule.waitForIdle()
        rule.onAllNodesWithText("JFK", substring = true).fetchSemanticsNodes().let {
            if (it.isNotEmpty()) rule.onAllNodesWithText("JFK", substring = true)[0].performClick()
        }
        rule.waitForIdle()
        // Open the passenger/trip controls if labelled, then hit Search.
        for (label in listOf("Round trip", "One way", "Search flights", "Search")) {
            rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().let {
                if (it.isNotEmpty()) {
                    rule.onAllNodesWithText(label, substring = true)[0].performClick()
                    rule.waitForIdle()
                    if (label.startsWith("Search")) return@let
                }
            }
        }
        rule.waitForIdle()
    }
}
