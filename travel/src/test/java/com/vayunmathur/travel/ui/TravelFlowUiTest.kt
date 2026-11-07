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
import com.vayunmathur.travel.network.TravelRaw
import com.vayunmathur.travel.network.travelTransport
import com.vayunmathur.travel.util.TravelViewModel
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

/** End-to-end page flows against a fake transport: change, cancel, confirmation, payment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelFlowUiTest {

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
        override suspend fun upsertBookedTrip(value: BookedTrip) { trips.value = trips.value.filter { it.orderId != value.orderId } + value }
    }

    private lateinit var repo: FakeRepo

    @BeforeTest fun open() {
        repo = FakeRepo(ApplicationProvider.getApplicationContext())
        repo.trips.value = listOf(BookedTrip(orderId = "ord_1", bookingReference = "ABC123", route = "SFO → JFK", departDate = "2027-06-24", amount = "250", currency = "USD", lastName = "Doe", tripId = "trip_1", status = "confirmed"))
        travelTransport = { _, url, _ ->
            when {
                url.contains("/bookings/") && url.contains("/cancel") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","refundTo":"card","commitable":true,"status":"quoted"}""")
                url.contains("/cancellations/") && url.contains("/confirm") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","status":"processing"}""")
                url.contains("/cancellations/") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","status":"cancelled"}""")
                url.contains("/bookings/") && url.contains("/changes") -> TravelRaw(200, """{"id":"req_1","offers":[{"id":"chg_1","changeTotalAmount":"50.00","changeTotalCurrency":"USD","newTotalAmount":"300.00","newTotalCurrency":"USD","slices":[{"id":"sl_1","origin":"SFO","destination":"JFK","departureAt":"2027-07-01T07:15:00","arrivalAt":"2027-07-01T15:50:00"}]}]}""")
                url.contains("/price?") -> TravelRaw(200, """{"outcome":"pay","amount":"50.00","currency":"USD"}""")
                url.contains("/changes/") && url.contains("/confirm") -> TravelRaw(200, """{"id":"chg_1","status":"processing"}""")
                url.contains("/changes/status") -> TravelRaw(200, """{"orderId":"ord_1","status":"changed"}""")
                url.contains("/bookings/") -> TravelRaw(200, """{"orderId":"ord_1","bookingReference":"ABC123","totalAmount":"250.00","currency":"USD","status":"confirmed","passengerNames":["Jane Doe"],"slices":[{"id":"sl_1","origin":"SFO","destination":"JFK","departureAt":"2027-06-24T07:15:00","arrivalAt":"2027-06-24T15:50:00"}],"canCancel":true,"canChange":true}""")
                url.contains("/checkout") -> TravelRaw(200, """{"clientSecret":"cs_1","publishableKey":"pk_1","quoteId":"q_1","totalAmount":"250.00","currency":"USD"}""")
                url.contains("/trips/") -> TravelRaw(200, """{"tripId":"trip_1","status":"confirmed","bookingReference":"ABC123","totalAmount":"250.00","currency":"USD"}""")
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

    @Test fun changePage_loadsOrderAndOffersFlow() {
        val v = vm()
        rule.setContent { ChangePage(stack(), v, Route.Change("ord_1")) }
        rule.waitUntil(5000) { rule.onAllNodesWithText("Pick a flight to change", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Change flights").assertIsDisplayed()
        assertTrue(v.orderDetail.value.order != null)
    }

    @Test fun cancellationPage_loadsQuote() {
        val v = vm()
        rule.setContent { CancellationPage(stack(), v, Route.Cancel("ord_1")) }
        rule.waitUntil(5000) { v.cancellation.value.quote != null }
        rule.onNodeWithText("Cancel order").assertIsDisplayed()
        assertTrue(v.cancellation.value.quote?.commitable == true)
    }

    @Test fun confirmationPage_showsBookedTrip() {
        rule.setContent { ConfirmationPage(stack(), vm(), Route.Confirmation("ord_1")) }
        rule.onNodeWithText("ABC123", substring = true).assertExists()
    }

    @Test fun stayConfirmationPage_showsTrip() {
        rule.setContent { StayConfirmationPage(stack(), vm(), Route.StayConfirmation("ord_1")) }
        rule.onNodeWithText("ABC123", substring = true).assertExists()
    }

    @Test fun orderDetailPage_loadsFromServer() {
        val v = vm()
        rule.setContent { OrderDetailPage(stack(), v, Route.OrderDetail("ord_1")) }
        rule.waitUntil(5000) { v.orderDetail.value.order != null }
        rule.onNodeWithText("Jane Doe").assertExists()
    }
}
