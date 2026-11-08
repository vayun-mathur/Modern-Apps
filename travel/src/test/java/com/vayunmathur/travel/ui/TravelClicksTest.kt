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
import com.vayunmathur.travel.network.CheckoutDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.CheckoutState
import com.vayunmathur.travel.util.OfferReviewState
import com.vayunmathur.travel.util.TravelViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Click paths: seat-map page, payment ready, awaiting-payment confirmation, search submit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelClicksTest {

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
    @BeforeTest fun open() { repo = FakeRepo(ApplicationProvider.getApplicationContext()) }
    private fun vm(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, repo)
    }
    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    @Test fun seatMapPage_renders() {
        rule.setContent { SeatMapPage(stack(), vm(), Route.SeatMap("off_1", "seg_1")) }
        rule.onNodeWithText("Choose seats").assertExists()
    }

    @Test fun payment_checkoutReady_showsPay() {
        val v = vm()
        v.reviewMutable.value = OfferReviewState(offer = OfferDto(offerId = "off_1", totalAmount = "250.00", slices = listOf(SliceDto(origin = "SFO", destination = "JFK", segments = listOf(SegmentDto(carrierIata = "AS"))))))
        v.checkoutMutable.value = CheckoutState.Ready(CheckoutDto(clientSecret = "cs", publishableKey = "pk", totalAmount = "250.00"))
        rule.setContent { PaymentPage(stack(), v, Route.Payment("off_1")) }
        rule.onNodeWithText("Pay securely").assertExists()
    }


    @Test fun flightSearch_searchButtonEnabledWithState() {
        var searched = false
        rule.setContent {
            FlightSearchForm(vm(), FlightFormState(tripType = TripType.ONE_WAY, origin = "SFO", destination = "JFK", originText = "SFO", destinationText = "JFK", depart = "2027-06-24"), {}, onSearch = { searched = true })
        }
        rule.onNodeWithText("Search flights").performClick()
        rule.runOnIdle { assertTrue(searched) }
    }

    @Test fun stayGuests_rendersForm() {
        rule.setContent { StayGuestsPage(stack(), vm()) }
        rule.onNodeWithText("Guest details").assertIsDisplayed()
        rule.onNodeWithText("Given name").assertExists()
    }
}
