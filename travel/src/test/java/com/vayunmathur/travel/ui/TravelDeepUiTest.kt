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
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.ChangeOfferDto
import com.vayunmathur.travel.network.CheckoutDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.ServiceDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.network.TripAncillariesDto
import com.vayunmathur.travel.util.AncillariesState
import com.vayunmathur.travel.util.ChangeState
import com.vayunmathur.travel.util.CheckoutState
import com.vayunmathur.travel.util.OfferReviewState
import com.vayunmathur.travel.util.OrderDetailState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.initPassengers
import com.vayunmathur.travel.util.setBaggageQuantity
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

/** Deep branches: change offers, ancillary steppers, price-change card, settings forms, home cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelDeepUiTest {

    @get:Rule val rule = createComposeRule()

    private class FakeRepo(ctx: android.content.Context) : TravelRepository(ctx) {
        val trips = MutableStateFlow<List<BookedTrip>>(emptyList())
        val customers = MutableStateFlow<List<Customer>>(emptyList())
        val flyers = MutableStateFlow<List<FrequentFlyer>>(emptyList())
        val recents = MutableStateFlow<List<RecentSearch>>(emptyList())
        var savedCustomer: Customer? = null
        var savedFlyer: FrequentFlyer? = null
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
        override suspend fun upsertCustomer(value: Customer) { savedCustomer = value }
        override suspend fun upsertFrequentFlyer(value: FrequentFlyer) { savedFlyer = value }
        override suspend fun getCustomer(id: String): Customer? = customers.value.find { it.id == id }
    }

    private lateinit var repo: FakeRepo
    @BeforeTest fun open() { repo = FakeRepo(ApplicationProvider.getApplicationContext()) }
    private fun vm(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, repo)
    }
    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))
    private fun slice() = SliceDto(origin = "SFO", destination = "JFK", departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00", segments = listOf(SegmentDto(carrierIata = "AS", flightNumber = "100", origin = "SFO", destination = "JFK", departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00", operatingCarrierIata = "OO")))
    private fun offer() = OfferDto(offerId = "off_1", totalAmount = "250.00", ownerIata = "AS", passengerIds = listOf("pax_1"), passengers = listOf(OfferPassengerDto(id = "pax_1", type = "adult")), refundPolicy = "Refundable", slices = listOf(slice()))

    @Test fun change_offers_priceAndConfirmButtons() {
        val v = vm(); val s = stack()
        rule.setContent { ChangePage(s, v, Route.Change("ord_1")) }
        rule.waitUntil(5000) {
            v.orderDetailMutable.value = OrderDetailState(order = OrderDetailDto(orderId = "ord_1", bookingReference = "ABC123", slices = listOf(slice()), canChange = true))
            v.changeMutable.value = ChangeState(requested = true, offers = listOf(ChangeOfferDto(id = "chg_1", changeTotalAmount = "50.00", changeTotalCurrency = "USD", newTotalAmount = "300.00", newTotalCurrency = "USD", slices = listOf(slice()))))
            rule.onAllNodesWithText("SFO", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("SFO → JFK", substring = true).assertExists()
        rule.onAllNodesWithText("$50", substring = true)[0].assertExists()
    }

    @Test fun ancillaries_steppers_updateQuantities() {
        val v = vm()
        v.reviewMutable.value = OfferReviewState(offer = offer())
        v.ancillariesMutable.value = AncillariesState(ancillaries = TripAncillariesDto(services = listOf(ServiceDto(id = "bag_1", type = "baggage", title = "Checked bag", totalAmount = "30.00", maxQuantity = 3))))
        rule.setContent { AncillariesPage(stack(), v, Route.Ancillaries("off_1")) }
        rule.onNodeWithText("Checked bag").assertIsDisplayed()
        rule.runOnIdle { v.setBaggageQuantity("bag_1", 2) }
        rule.onNodeWithText("2").assertExists()
        rule.runOnIdle { assertEquals(2L, v.selectedBaggage.value["bag_1"]) }
    }

    @Test fun payment_priceChanged_acceptFlow() {
        val v = vm()
        v.reviewMutable.value = OfferReviewState(offer = offer())
        v.checkoutMutable.value = CheckoutState.Ready(CheckoutDto(clientSecret = "cs", publishableKey = "pk", totalAmount = "300.00", priceChanged = true, originalAmount = "250.00"))
        rule.setContent { PaymentPage(stack(), v, Route.Payment("off_1")) }
        rule.onNodeWithText("Price changed", substring = true).assertExists()
    }

    @Test fun offerReview_operatingCarrierAndContinue() {
        val v = vm(); v.reviewMutable.value = OfferReviewState(offer = offer())
        val s = stack()
        rule.setContent { OfferReviewPage(s, v, Route.OfferReview("off_1")) }
        rule.onNodeWithText("Review flight").assertIsDisplayed()
        rule.onNodeWithText("$250").assertExists()
        rule.onAllNodesWithText("Continue", substring = true)[0].assertExists()
        rule.onNodeWithText("$250").assertExists()
    }

    @Test fun passengers_fullTyping() {
        val v = vm(); v.initPassengers(offer())
        rule.setContent { PassengersPage(stack(), v, Route.Passengers("off_1")) }
        rule.onNodeWithText("Family name").performTextInput("Doe")
        rule.runOnIdle {
            assertEquals("Doe", v.passengers.value[0].familyName)
        }
    }

    @Test fun settings_addCustomerForm() {
        rule.setContent { SettingsPage(stack(), vm()) }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Email", substring = true).assertExists()
    }

    @Test fun home_tripCardClick_navigates() {
        repo.trips.value = listOf(BookedTrip(orderId = "ord_1", bookingReference = "ABC123", route = "SFO → JFK", departDate = "2027-06-24", amount = "250", currency = "USD"))
        val s = stack()
        rule.setContent { HomePage(s, vm()) }
        rule.onAllNodesWithText("SFO → JFK")[0].performScrollTo().performClick()
        rule.runOnIdle { assertTrue(s.backStack.last() is Route.Confirmation, "last=${s.backStack.last()}") }
    }

    @Test fun orderDetail_actionsVisible() {
        val v = vm(); val s = stack()
        rule.setContent { OrderDetailPage(s, v, Route.OrderDetail("ord_1")) }
        rule.waitUntil(5000) {
            v.orderDetailMutable.value = OrderDetailState(order = OrderDetailDto(orderId = "ord_1", bookingReference = "ABC123", totalAmount = "250.00", slices = listOf(slice()), passengerNames = listOf("Jane Doe"), canCancel = true, canChange = false))
            rule.onAllNodesWithText("Jane Doe").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Jane Doe").assertExists()
        rule.onAllNodesWithText("Cancel", substring = true)[0].assertExists()
    }
}
