package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
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
import com.vayunmathur.travel.network.CancellationDto
import com.vayunmathur.travel.network.ChangeOfferDto
import com.vayunmathur.travel.network.ChangePriceDto
import com.vayunmathur.travel.network.CheckoutDto
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.network.StayBookingHotelDto
import com.vayunmathur.travel.util.CancellationState
import com.vayunmathur.travel.util.ChangeState
import com.vayunmathur.travel.util.CheckoutState
import com.vayunmathur.travel.util.OrderDetailState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.TripPollState
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
 * C5 page branches: change-offer pricing variants, cancellation quote/done
 * states, confirmation statuses, hotel order detail, price-change notice,
 * and the date-range dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelPagesC5Test {

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
        override suspend fun getAllBookedTrips(): List<BookedTrip> = trips.value
        override suspend fun getBookedTrip(orderId: String): BookedTrip? = trips.value.find { it.orderId == orderId }
        override suspend fun insertRecent(value: RecentSearch) {}
        override suspend fun clearRecent() {}
        override suspend fun trimRecent(keep: Int) {}
        override suspend fun upsertBookedTrip(value: BookedTrip) {
            trips.value = trips.value.filter { it.orderId != value.orderId } + value
        }
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

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Home))

    private fun slice(origin: String = "SFO", destination: String = "JFK", id: String = "sli_1") = SliceDto(
        id = id, origin = origin, destination = destination,
        departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00",
        segments = listOf(
            SegmentDto(
                id = "seg_$id", carrierIata = "AS", flightNumber = "100",
                origin = origin, destination = destination,
                departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00",
            )
        ),
    )

    private fun order() = OrderDetailDto(
        orderId = "ord_1", bookingReference = "ABC123", totalAmount = "250.00",
        currency = "USD", status = "confirmed",
        slices = listOf(slice(), slice("JFK", "SFO", "sli_2")),
        canChange = true, canCancel = true,
    )

    private fun trip(status: String, failure: String = "", expires: String = "") = BookedTrip(
        orderId = "ord_1", bookingReference = "ABC123", route = "SFO → JFK",
        departDate = "2027-06-24", amount = "250.00", currency = "USD",
        status = status, tripId = "trip_1", failureMessage = failure,
        checkoutExpiresAt = expires,
    )

    // -- Order detail (stateless screen) -------------------------------------

    @Test
    fun orderDetail_hotelCard_rendersAllHotelLines() {
        val hotelOrder = order().copy(
            hotel = StayBookingHotelDto(
                name = "Grand Hotel", address = "1 Main St",
                checkIn = "2027-06-24", checkOut = "2027-06-26",
                roomName = "Deluxe King", boardName = "Breakfast included",
            ),
            ticketNumbers = listOf("0011234567890"),
        )
        rule.setContent {
            OrderDetailScreen(
                state = com.vayunmathur.travel.util.OrderDetailUiState(order = hotelOrder),
                actions = com.vayunmathur.travel.util.OrderDetailActions.Noop,
            )
        }
        rule.onNodeWithText("Grand Hotel").assertIsDisplayed()
        rule.onNodeWithText("1 Main St").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("2027-06-24 → 2027-06-26").performScrollTo().assertExists()
        rule.onNodeWithText("Deluxe King · Breakfast included").performScrollTo().assertExists()
        rule.onNodeWithText("0011234567890").performScrollTo().assertExists()
        rule.onNodeWithText("Flight 1").performScrollTo().assertExists()
        rule.onNodeWithText("Flight 2").performScrollTo().assertExists()
    }

    @Test
    fun orderDetail_hotelCard_blankFieldsAreOmitted() {
        val hotelOrder = order().copy(hotel = StayBookingHotelDto(name = "Airport Inn"))
        rule.setContent {
            OrderDetailScreen(
                state = com.vayunmathur.travel.util.OrderDetailUiState(order = hotelOrder),
                actions = com.vayunmathur.travel.util.OrderDetailActions.Noop,
            )
        }
        rule.onNodeWithText("Airport Inn").assertIsDisplayed()
    }

    @Test
    fun orderDetail_priceChanged_requiresAcceptBeforePay() {
        var accepted = 0
        var paid = 0
        rule.setContent {
            OrderDetailScreen(
                state = com.vayunmathur.travel.util.OrderDetailUiState(
                    order = order(),
                    checkout = CheckoutState.Ready(
                        CheckoutDto(
                            clientSecret = "cs", publishableKey = "pk",
                            totalAmount = "300.00", currency = "USD",
                            priceChanged = true, originalAmount = "250.00",
                        )
                    ),
                ),
                actions = object : com.vayunmathur.travel.util.OrderDetailActions {
                    override fun acceptNewPrice() { accepted++ }
                    override fun completePayment() { paid++ }
                },
                pendingCheckout = true,
            )
        }
        rule.onNodeWithText("Price changed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Accept new price").performScrollTo().assertExists()
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, accepted) }
        // The screen is stateless: until the owner re-renders with
        // acceptedNewPrice=true the pay button stays gated, so clicking it
        // must not fire completePayment.
        rule.onNodeWithText("Complete payment").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(0, paid) }
    }

    @Test
    fun orderDetail_checkoutError_showsMessage() {
        rule.setContent {
            OrderDetailScreen(
                state = com.vayunmathur.travel.util.OrderDetailUiState(
                    order = order(),
                    checkout = CheckoutState.Error("Card setup failed"),
                ),
                actions = com.vayunmathur.travel.util.OrderDetailActions.Noop,
                pendingCheckout = true,
            )
        }
        rule.onNodeWithText("Card setup failed").performScrollTo().assertIsDisplayed()
    }

    // -- Change page ----------------------------------------------------------

    private fun setChange(v: TravelViewModel, change: ChangeState, text: String) {
        val s = stack()
        rule.setContent { ChangePage(s, v, Route.Change("ord_1")) }
        rule.waitUntil(5_000) {
            v.orderDetailMutable.value = OrderDetailState(order = order())
            v.changeMutable.value = change
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun change_notRequested_sliceSelectionAndDateGate() {
        val v = vm()
        setChange(v, ChangeState(), "Pick a flight to change")
        rule.onNodeWithText("Pick a flight to change").assertIsDisplayed()
        rule.onAllNodesWithText("JFK → SFO")[0].performScrollTo().performClick()
        rule.onNodeWithText("Find change options").performScrollTo().assertExists()
    }

    @Test
    fun change_offers_pricingVariants() {
        val v = vm()
        val offers = listOf(
            ChangeOfferDto(id = "c_pay", changeTotalAmount = "50.00", changeTotalCurrency = "USD", slices = listOf(slice()), description = "Same cabin"),
            ChangeOfferDto(id = "c_refund", changeTotalAmount = "-20.00", changeTotalCurrency = "USD", slices = listOf(slice())),
            ChangeOfferDto(id = "c_blank", changeTotalAmount = "", changeTotalCurrency = "USD", slices = listOf(slice())),
            ChangeOfferDto(id = "c_even", changeTotalAmount = "0.00", changeTotalCurrency = "USD", slices = listOf(slice())),
        )
        setChange(
            v,
            ChangeState(requested = true, offers = offers, checkoutUrl = "https://pay.example", polling = true),
            "Change options",
        )
        rule.onNodeWithText("Change options").assertIsDisplayed()
        rule.onNodeWithText("An extra payment is needed to finish this change.", substring = true).assertExists()
        rule.onNodeWithText("Change is processing…").assertExists()
        rule.onAllNodesWithText("Extra to pay", substring = true)[0].performScrollTo().assertExists()
        rule.onAllNodesWithText("Refund", substring = true)[0].performScrollTo().assertExists()
        rule.onAllNodesWithText("No price change", substring = true)[0].performScrollTo().assertExists()
        rule.onNodeWithText("Same cabin").performScrollTo().assertExists()
    }

    @Test
    fun change_offers_pricedOutcomeAndConfirmButton() {
        val v = vm()
        val offers = listOf(
            ChangeOfferDto(id = "c_pay", changeTotalAmount = "50.00", changeTotalCurrency = "USD", slices = listOf(slice())),
        )
        setChange(
            v,
            ChangeState(
                requested = true, offers = offers,
                price = ChangePriceDto(outcome = "pay", amount = "50.00", currency = "USD"),
                pricingOfferId = "c_pay",
            ),
            "Select & confirm",
        )
        rule.onNodeWithText("Select & confirm").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Extra to pay", substring = true)[0].assertExists()
    }

    @Test
    fun change_loadingErrorAndEmptyStates() {
        val v = vm()
        setChange(v, ChangeState(requested = true, loading = true), "Change flights")
        rule.waitUntil(5_000) {
            v.changeMutable.value = ChangeState(requested = true, error = "Change lookup failed")
            rule.onAllNodesWithText("Change lookup failed").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Change lookup failed").assertIsDisplayed()
        rule.waitUntil(5_000) {
            v.changeMutable.value = ChangeState(requested = true)
            rule.onAllNodesWithText("No change options available.").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("No change options available.").assertIsDisplayed()
    }

    @Test
    fun change_done_showsConfirmation() {
        val v = vm()
        setChange(v, ChangeState(done = true), "Change confirmed")
        rule.onNodeWithText("Change confirmed", substring = true).assertExists()
    }

    // -- Cancellation page ------------------------------------------------------

    private fun setCancellation(v: TravelViewModel, state: CancellationState, text: String) {
        val s = stack()
        rule.setContent { CancellationPage(s, v, Route.Cancel("ord_1")) }
        rule.waitUntil(5_000) {
            v.cancellationMutable.value = state
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cancellation_quoteWithPenaltyMessageAndPolling() {
        val v = vm()
        setCancellation(
            v,
            CancellationState(
                quote = CancellationDto(
                    id = "can_1", orderId = "ord_1", refundAmount = "180.00",
                    refundCurrency = "USD", penaltyAmount = "70.00",
                    commitable = true, message = "Airline fee applies",
                ),
                polling = true,
            ),
            "Cancel this order?",
        )
        rule.onNodeWithText("Cancel this order?").assertIsDisplayed()
        rule.onNodeWithText("Airline fee applies").performScrollTo().assertExists()
        rule.onNodeWithText("Cancellation is processing…").performScrollTo().assertExists()
        rule.onAllNodesWithText("$180", substring = true)[0].assertExists()
        rule.onAllNodesWithText("$70", substring = true)[0].performScrollTo().assertExists()
    }

    @Test
    fun cancellation_doneShowsRefundDestinationFallback() {
        val v = vm()
        setCancellation(
            v,
            CancellationState(
                done = true,
                quote = CancellationDto(id = "can_1", refundAmount = "200.00", refundCurrency = "USD"),
            ),
            "Order cancelled",
        )
        rule.onNodeWithText("Order cancelled").assertIsDisplayed()
        rule.onAllNodesWithText("original payment", substring = true)[0].assertExists()
    }

    @Test
    fun cancellation_errorWithoutQuote_showsStatusBox() {
        val v = vm()
        setCancellation(v, CancellationState(error = "Quote expired"), "Quote expired")
        rule.onNodeWithText("Quote expired").assertIsDisplayed()
    }

    // -- Confirmation pages ------------------------------------------------------

    @Test
    fun confirmation_failedShowsFailureMessage() {
        repo.trips.value = listOf(trip("failed", failure = "Card declined by issuer"))
        val v = vm()
        rule.setContent { ConfirmationPage(stack(), v, Route.Confirmation("ord_1")) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Card declined by issuer").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Booking failed", substring = true).assertExists()
        rule.onNodeWithText("Card declined by issuer").performScrollTo().assertExists()
    }

    @Test
    fun confirmation_processingAndPartialShowRefresh() {
        repo.trips.value = listOf(trip("processing"))
        val v = vm()
        rule.setContent { ConfirmationPage(stack(), v, Route.Confirmation("ord_1")) }
        rule.onAllNodesWithText("Confirming payment…")[0].assertExists()
        // Swap to partial: refresh button appears for polling statuses.
        repo.trips.value = listOf(trip("partial"))
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Refresh status").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Refresh status").performScrollTo().assertExists()
    }

    @Test
    fun confirmation_pendingExpiredShowsExpiredNotice() {
        repo.trips.value = listOf(trip("pending_payment", expires = "2000-01-01T00:00:00Z"))
        val v = vm()
        rule.setContent { ConfirmationPage(stack(), v, Route.Confirmation("ord_1")) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("booking session has expired", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("Payment pending", substring = true)[0].assertExists()
    }

    @Test
    fun confirmation_pendingWithErrorsShowsBothMessages() {
        repo.trips.value = listOf(trip("pending_payment", expires = "2999-01-01T00:00:00Z"))
        val v = vm()
        rule.setContent { ConfirmationPage(stack(), v, Route.Confirmation("ord_1")) }
        rule.waitUntil(5_000) {
            v.tripPollMutable.value = TripPollState.Error("Poll failed")
            v.checkoutMutable.value = CheckoutState.Error("Checkout failed")
            rule.onAllNodesWithText("Checkout failed").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Poll failed").performScrollTo().assertExists()
        rule.onNodeWithText("Checkout failed").assertExists()
        rule.onNodeWithText("Complete payment").performScrollTo().assertExists()
    }

    @Test
    fun confirmation_unknownTripOffersBackToSearch() {
        val v = vm()
        val s = stack()
        rule.setContent { ConfirmationPage(s, v, Route.Confirmation("missing")) }
        rule.onNodeWithText("Trip not found.").assertIsDisplayed()
        rule.onNodeWithText("Back to search").performClick()
        rule.runOnIdle { assertTrue(s.backStack.last() is Route.Home) }
    }

    @Test
    fun confirmation_doneButtonsNavigate() {
        repo.trips.value = listOf(trip("confirmed"))
        val v = vm()
        val s = stack()
        rule.setContent { ConfirmationPage(s, v, Route.Confirmation("ord_1")) }
        rule.onNodeWithText("You're booked!").assertIsDisplayed()
        rule.onNodeWithText("View full itinerary").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(s.backStack.last() is Route.OrderDetail, "last=${s.backStack.last()}") }
        assertEquals("ABC123", repo.trips.value.single().bookingReference)
    }

    @Test
    fun stayConfirmation_pendingAndConfirmedStates() {
        repo.trips.value = listOf(trip("confirmed"))
        val v = vm()
        rule.setContent { StayConfirmationPage(stack(), v, Route.StayConfirmation("ord_1")) }
        rule.onNodeWithText("You're booked!").assertIsDisplayed()
        rule.onNodeWithText("ABC123").assertExists()

        repo.trips.value = listOf(trip("pending_payment", expires = "2999-01-01T00:00:00Z"))
        rule.waitUntil(5_000) {
            v.tripPollMutable.value = TripPollState.Error("Stay poll failed")
            rule.onAllNodesWithText("Complete payment").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Complete payment").performScrollTo().assertExists()
    }

    // -- Date range --------------------------------------------------------------

    @Test
    fun dateRangeFields_openDialogAndCancelKeepsValue() {
        var range: Pair<String, String>? = null
        rule.setContent {
            DateRangeFields(
                startLabel = "Depart", endLabel = "Return",
                start = "2027-06-24", end = "2027-06-28",
                onRange = { s, e -> range = s to e },
            )
        }
        rule.onNodeWithText("Depart").assertIsDisplayed()
        rule.onNodeWithText("Depart").performClick()
        rule.onNodeWithText("Select dates").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(null, range) }
    }

    @Test
    fun dateRangeFields_invalidIsoFallsBackToRawText() {
        rule.setContent {
            DateRangeFields(
                startLabel = "Check-in", endLabel = "Check-out",
                start = "not-a-date", end = "",
                onRange = { _, _ -> },
                allowSameDay = false,
            )
        }
        rule.onNodeWithText("not-a-date").assertIsDisplayed()
        rule.onNodeWithText("Check-out").assertIsDisplayed()
    }
}
