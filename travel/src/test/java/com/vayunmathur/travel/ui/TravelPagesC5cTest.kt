package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performScrollTo
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
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.StayRatesDto
import com.vayunmathur.travel.network.StayRoomDto
import com.vayunmathur.travel.platform.OfferReviewState
import com.vayunmathur.travel.platform.StayRatesState
import com.vayunmathur.travel.platform.TravelViewModel
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
 * C5 page branches, part 3: a fully populated stay detail with rate
 * selection, and the offer review's held-price / error / continue
 * branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelPagesC5cTest {

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

    private fun rates() = StayRatesDto(
        name = "C5 Grand Hotel",
        rating = 4,
        reviewScore = 8.7,
        address = "1 C5 Plaza, Springfield",
        amenities = listOf("Pool", "Spa", "WiFi", "Gym", "Bar", "Parking", "Sauna", "Desk", "Extra"),
        description = "A grand C5 hotel by the river.",
        checkInTime = "15:00",
        checkOutTime = "11:00",
        rooms = listOf(
            StayRoomDto(
                name = "Deluxe King",
                rates = listOf(
                    StayRateDto(id = "rate-1", boardType = "breakfast", totalAmount = "180.00", cancellation = "Free cancellation"),
                    StayRateDto(id = "rate-2", boardType = "", totalAmount = "150.00", cancellation = "Non-refundable"),
                ),
            ),
            StayRoomDto(
                name = "",
                rates = listOf(
                    StayRateDto(id = "rate-3", boardType = "half board", totalAmount = "210.00", cancellation = "Free cancellation"),
                ),
            ),
        ),
    )

    @Test
    fun stayDetail_fullHotel_showsRoomsAndSelectsRate() {
        val v = vm()
        val s = stack()
        // The page loads rates on entry; serve them (and the quote that a
        // rate tap requests) from the scripted transport.
        val ratesJson = com.vayunmathur.travel.network.travelJson.encodeToString(
            StayRatesDto.serializer(), rates(),
        )
        com.vayunmathur.travel.network.travelTransport = { _, url, _ ->
            when {
                url.contains("/rates") -> com.vayunmathur.travel.network.TravelRaw(200, ratesJson)
                url.contains("/quote") -> com.vayunmathur.travel.network.TravelRaw(
                    200, """{"id":"q-c5","totalAmount":"150.00","totalCurrency":"USD"}""",
                )
                else -> com.vayunmathur.travel.network.TravelRaw(404, """{"message":"not found"}""")
            }
        }
        rule.setContent { StayDetailPage(s, v, Route.StayDetail("sr-c5", "C5 Grand Hotel")) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Deluxe King").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("1 C5 Plaza, Springfield").performScrollTo().assertExists()
        rule.onNodeWithText("A grand C5 hotel by the river.", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Pool", substring = true).performScrollTo().assertExists()
        rule.onAllNodesWithText("Free cancellation")[0].performScrollTo().assertExists()
        // Blank board type falls back to the room-only label (merged into
        // the rate card's semantics, so it is matched as a substring).
        rule.onAllNodesWithText("Room only", substring = true)[0].assertExists()
        // Bring the rate cards fully on screen, then tap the $150 one.
        rule.onNodeWithText("Deluxe King").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onAllNodesWithText("$150")[0].performClick()
        rule.waitForIdle()
        // The choice lives in the route, not the ViewModel: tapping the
        // $150 rate pushes StayGuests carrying its ID (the guests entry
        // resolves + quotes it on entry).
        assertEquals(Route.StayGuests("rate-2"), s.last())
    }

    @Test
    fun stayDetail_loadingAndError_states() {
        val v = vm()
        val s = stack()
        rule.setContent { StayDetailPage(s, v, Route.StayDetail("sr-c5e", "")) }
        rule.waitUntil(5_000) {
            v.stayRatesMutable.value = StayRatesState(error = "C5 stay load failed")
            rule.onAllNodesWithText("C5 stay load failed").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("C5 stay load failed").assertIsDisplayed()
    }

    private fun offer(expiresAt: String = "") = OfferDto(
        offerId = "off-c5", totalAmount = "250.00", currency = "USD",
        owner = "C5 Air", ownerIata = "C5",
        passengerIds = listOf("pax_1"),
        passengers = listOf(OfferPassengerDto(id = "pax_1", type = "adult")),
        expiresAt = expiresAt,
        slices = listOf(
            SliceDto(
                origin = "SFO", destination = "JFK", durationMinutes = 315, stops = 0,
                departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00",
                segments = listOf(
                    SegmentDto(
                        id = "seg-c5", carrier = "C5 Air", carrierIata = "C5", flightNumber = "42",
                        origin = "SFO", destination = "JFK",
                        departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun offerReview_heldPrice_andContinue_navigatesToPassengers() {
        val v = vm()
        val s = stack()
        // The page re-verifies the offer on entry; answer with the same offer
        // so verification succeeds and Continue stays enabled.
        val json = com.vayunmathur.travel.network.travelJson.encodeToString(
            OfferDto.serializer(), offer(expiresAt = "2999-01-01T00:00:00Z"),
        )
        com.vayunmathur.travel.network.travelTransport = { _, url, _ ->
            if (url.contains("/offer")) com.vayunmathur.travel.network.TravelRaw(200, json)
            else com.vayunmathur.travel.network.TravelRaw(404, """{"message":"not found"}""")
        }
        v.reviewMutable.value = OfferReviewState(offer = offer(expiresAt = "2999-01-01T00:00:00Z"))
        rule.setContent { OfferReviewPage(s, v, Route.OfferReview("off-c5")) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Price held", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Continue").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(s.last() is Route.Passengers, "continue goes to passengers, was ${s.last()}")
    }

    @Test
    fun offerReview_error_blocksContinue() {
        val v = vm()
        val s = stack()
        rule.setContent { OfferReviewPage(s, v, Route.OfferReview("off-c5")) }
        rule.waitUntil(5_000) {
            v.reviewMutable.value = OfferReviewState(
                offer = offer(), error = "C5 price check failed",
            )
            rule.onAllNodesWithText("C5 price check failed").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("C5 price check failed").performScrollTo().assertExists()
        rule.onNodeWithText("Continue").performScrollTo().performClick()
        rule.waitForIdle()
        // Disabled button: no navigation happened.
        assertEquals(Route.FlightsHome, s.last())
    }
}
