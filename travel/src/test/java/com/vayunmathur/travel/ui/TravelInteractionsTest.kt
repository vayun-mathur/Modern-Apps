package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.CancellationDto
import com.vayunmathur.travel.network.ChangeOfferDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.StayRatesDto
import com.vayunmathur.travel.network.StayRoomDto
import com.vayunmathur.travel.util.CancellationState
import com.vayunmathur.travel.util.ChangeState
import com.vayunmathur.travel.util.OfferReviewState
import com.vayunmathur.travel.util.OrderDetailState
import com.vayunmathur.travel.util.StayRatesState
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

/** Seeded detail/change/cancel/stay-rate states and form typing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelInteractionsTest {

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
    private fun slice() = SliceDto(origin = "SFO", destination = "JFK", departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00", segments = listOf(SegmentDto(carrierIata = "AS", flightNumber = "100", origin = "SFO", destination = "JFK", departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00")))
    private fun offer() = OfferDto(offerId = "off_1", totalAmount = "250.00", passengerIds = listOf("pax_1"), passengers = listOf(OfferPassengerDto(id = "pax_1", type = "adult")), slices = listOf(slice()))

    @Test fun change_withOffers_showsOffer() {
        val v = vm()
        v.orderDetailMutable.value = OrderDetailState(order = OrderDetailDto(orderId = "ord_1", bookingReference = "ABC123", slices = listOf(slice()), canChange = true))
        v.changeMutable.value = ChangeState(requested = true, offers = listOf(ChangeOfferDto(id = "chg_1", newTotalAmount = "300.00", newTotalCurrency = "USD", slices = listOf(slice()))))
        rule.setContent { ChangePage(stack(), v, Route.Change("ord_1")) }
        rule.runOnIdle {
            v.orderDetailMutable.value = OrderDetailState(order = OrderDetailDto(orderId = "ord_1", bookingReference = "ABC123", slices = listOf(slice()), canChange = true))
            v.changeMutable.value = ChangeState(requested = true, offers = listOf(ChangeOfferDto(id = "chg_1", newTotalAmount = "300.00", newTotalCurrency = "USD", slices = listOf(slice()))))
        }
        rule.onNodeWithText("Change flights").assertIsDisplayed()
        rule.onNodeWithText("SFO → JFK", substring = true).assertExists()
    }

    @Test fun cancellation_error_showsMessage() {
        val v = vm()
        rule.setContent { CancellationPage(stack(), v, Route.Cancel("ord_1")) }
        rule.runOnIdle { v.cancellationMutable.value = CancellationState(error = "Cannot cancel") }
        rule.onNodeWithText("Cancel order").assertIsDisplayed()
        rule.onNodeWithText("Cannot cancel").assertExists()
    }

    @Test fun cancellation_done_showsCancelled() {
        val v = vm()
        v.cancellationMutable.value = CancellationState(done = true)
        rule.setContent { CancellationPage(stack(), v, Route.Cancel("ord_1")) }
        rule.runOnIdle { v.cancellationMutable.value = CancellationState(done = true) }
        rule.onNodeWithText("Order cancelled", substring = true).assertExists()
    }

    @Test fun stayDetail_withRates_showsRoom() {
        val v = vm()
        v.stayRatesMutable.value = StayRatesState(rates = StayRatesDto(name = "Grand Hotel", rooms = listOf(StayRoomDto(name = "Deluxe King", rates = listOf(StayRateDto(id = "r1", boardType = "breakfast", totalAmount = "180.00", cancellation = "Free cancellation"))))))
        rule.setContent { StayDetailPage(stack(), v, Route.StayDetail("sr_1", "Grand Hotel")) }
        rule.runOnIdle { v.stayRatesMutable.value = StayRatesState(rates = StayRatesDto(name = "Grand Hotel", rooms = listOf(StayRoomDto(name = "Deluxe King", rates = listOf(StayRateDto(id = "r1", boardType = "breakfast", totalAmount = "180.00", cancellation = "Free cancellation")))))) }
        rule.onAllNodesWithText("Grand Hotel")[0].assertIsDisplayed()
        rule.onNodeWithText("Deluxe King").assertExists()
    }

    @Test fun orderDetailPage_withOrder_showsReference() {
        val v = vm()
        v.orderDetailMutable.value = OrderDetailState(order = OrderDetailDto(orderId = "ord_1", bookingReference = "ABC123", totalAmount = "250.00", slices = listOf(slice()), passengerNames = listOf("Jane Doe")))
        rule.setContent { OrderDetailPage(stack(), v, Route.OrderDetail("ord_1")) }
        rule.runOnIdle { v.orderDetailMutable.value = OrderDetailState(order = OrderDetailDto(orderId = "ord_1", bookingReference = "ABC123", totalAmount = "250.00", slices = listOf(slice()), passengerNames = listOf("Jane Doe"))) }
        rule.onNodeWithText("ABC123", substring = true).assertExists()
        rule.onNodeWithText("Jane Doe").assertExists()
    }

    @Test fun passengers_typingUpdates() {
        val v = vm(); v.initPassengers(offer())
        rule.setContent { PassengersPage(stack(), v, Route.Passengers("off_1")) }
        rule.onNodeWithText("Given name").performTextInput("Jane")
        rule.runOnIdle { kotlin.test.assertEquals("Jane", v.passengers.value[0].givenName) }
    }

    @Test fun offerReview_continueVisible() {
        val v = vm(); v.reviewMutable.value = OfferReviewState(offer = offer())
        rule.setContent { OfferReviewPage(stack(), v, Route.OfferReview("off_1")) }
        rule.onNodeWithText("Continue", substring = true).assertExists()
    }
}
