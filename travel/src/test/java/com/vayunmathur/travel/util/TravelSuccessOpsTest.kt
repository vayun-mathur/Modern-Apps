package com.vayunmathur.travel.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.StayGuestInputDto
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.TravelRaw
import com.vayunmathur.travel.network.travelTransport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Success paths through a fake travel transport: search → trip → ancillaries → checkout → confirm, stays, servicing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelSuccessOpsTest {

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

    private val offerJson = """{"offerId":"off_1","itineraryId":"itin_1","totalAmount":"250.00","currency":"USD","passengerIds":["pax_1"],"passengers":[{"id":"pax_1","type":"adult"}],"slices":[{"origin":"SFO","destination":"JFK","departureAt":"2027-06-24T07:15:00","arrivalAt":"2027-06-24T15:50:00","segments":[{"carrierIata":"AS","flightNumber":"100","origin":"SFO","destination":"JFK","departureAt":"2027-06-24T07:15:00","arrivalAt":"2027-06-24T15:50:00"}]}]}"""

    @BeforeTest fun open() {
        repo = FakeRepo(ApplicationProvider.getApplicationContext())
        travelTransport = { _, url, _ ->
            when {
                url.contains("/flights") -> TravelRaw(200, """{"offerRequestId":"req_1","offers":[$offerJson]}""")
                url.contains("/offer?") -> TravelRaw(200, offerJson)
                url.contains("/ancillaries") && url.contains("/trips/") -> TravelRaw(200, """{"status":"ok","services":[{"id":"bag_1","type":"baggage","title":"Checked bag","totalAmount":"30.00","totalCurrency":"USD","maxQuantity":3}],"seatMaps":[]}""")
                url.contains("/checkout") -> TravelRaw(200, """{"clientSecret":"cs_1","publishableKey":"pk_1","quoteId":"q_1","totalAmount":"250.00","currency":"USD"}""")
                url.contains("/confirm") && url.contains("/trips/") -> TravelRaw(200, """{"tripId":"trip_1","status":"confirmed","bookingReference":"ABC123","totalAmount":"250.00","currency":"USD"}""")
                url.endsWith("/trips") -> TravelRaw(200, """{"tripId":"trip_1","status":"created","totalAmount":"250.00","currency":"USD"}""")
                url.contains("/trips/") -> TravelRaw(200, """{"tripId":"trip_1","status":"confirmed","bookingReference":"ABC123","totalAmount":"250.00","currency":"USD"}""")
                url.contains("/bookings/") && url.contains("/cancel") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","commitable":true,"status":"quoted"}""")
                url.contains("/cancellations/") && url.contains("/confirm") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","status":"processing"}""")
                url.contains("/cancellations/") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","status":"cancelled"}""")
                url.contains("/changes") && url.contains("/bookings/") -> TravelRaw(200, """{"id":"chgreq_1","offers":[{"id":"chg_1","changeTotalAmount":"50.00","changeTotalCurrency":"USD","newTotalAmount":"300.00","newTotalCurrency":"USD","slices":[{"origin":"SFO","destination":"JFK","departureAt":"2027-07-01T07:15:00","arrivalAt":"2027-07-01T15:50:00"}]}]}""")
                url.contains("/price?") -> TravelRaw(200, """{"outcome":"pay","amount":"50.00","currency":"USD"}""")
                url.contains("/changes/") && url.contains("/confirm") -> TravelRaw(200, """{"id":"chg_1","status":"processing"}""")
                url.contains("/changes/status") -> TravelRaw(200, """{"orderId":"ord_1","status":"changed"}""")
                url.contains("/bookings/") -> TravelRaw(200, """{"orderId":"ord_1","bookingReference":"ABC123","totalAmount":"250.00","currency":"USD","status":"confirmed","passengerNames":["Jane Doe"],"slices":[{"origin":"SFO","destination":"JFK","departureAt":"2027-06-24T07:15:00","arrivalAt":"2027-06-24T15:50:00"}],"canCancel":true,"canChange":true}""")
                url.contains("/stays/search") -> TravelRaw(200, """[{"id":"sr_1","name":"Grand Hotel","rating":5,"cheapestAmount":"180.00","cheapestCurrency":"USD"}]""")
                url.contains("/stays/rates") -> TravelRaw(200, """{"name":"Grand Hotel","rooms":[{"name":"Deluxe King","rates":[{"id":"r1","boardType":"breakfast","totalAmount":"180.00","totalCurrency":"USD","cancellation":"Free cancellation"}]}]}""")
                url.contains("/stays/quote") -> TravelRaw(200, """{"id":"quote_1","totalAmount":"180.00","totalCurrency":"USD"}""")
                url.contains("/stays/bookings") -> TravelRaw(200, """{"id":"stay_1","reference":"STAY123","status":"pending_payment","accommodationName":"Grand Hotel","totalAmount":"180.00","totalCurrency":"USD","clientSecret":"cs_s","publishableKey":"pk_s"}""")
                else -> TravelRaw(404, """{"message":"not found"}""")
            }
        }
    }

    @AfterTest fun clear() { travelTransport = null }

    private fun vm(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, repo)
    }
    private fun idle() { repeat(3) { ShadowLooper.idleMainLooper() } }

    @Test fun flightSearch_success_populatesOffers() {
        val v = vm()
        v.searchFlights(FlightQuery(slices = "SFO:JFK:2027-06-24", adults = 1)); idle()
        assertEquals(1, v.flights.value.allOffers.size)
        assertEquals("off_1", v.flights.value.allOffers[0].offerId)
        v.setSort(OfferSort.CHEAPEST); v.toggleAirlineFilter("AS"); v.clearFilters(); idle()
    }

    @Test fun bookingFlow_createAncillariesCheckoutConfirm() {
        val v = vm()
        v.searchFlights(FlightQuery(slices = "SFO:JFK:2027-06-24", adults = 1)); idle()
        val offer = v.flights.value.allOffers[0]
        v.selectOffer(offer); v.initPassengers(offer); idle()
        v.createTrip(); idle()
        assertTrue(v.booking.value is BookingState.TripReady, "booking=${v.booking.value}")
        v.loadAncillaries("trip_1"); idle()
        assertEquals(1, v.ancillaries.value.ancillaries?.services?.size)
        v.setBaggageQuantity("bag_1", 1); v.saveAncillaries {}; idle()
        v.startCheckout(); idle()
        assertTrue(v.checkout.value is CheckoutState.Ready, "checkout=${v.checkout.value}")
        v.confirmPaidTrip("trip_1"); idle()
        v.pollTrip("trip_1"); idle(); v.stopTripPoll()
    }

    @Test fun staysFlow_searchRatesBook() {
        val v = vm()
        v.searchStays("Paris", "2027-06-24", "2027-06-26", 1, 2, null, null); idle()
        assertEquals(1, v.stayResults.value.results.size)
        v.loadStayRates("sr_1", "Grand Hotel"); idle()
        assertEquals("Grand Hotel", v.stayRates.value.rates?.name)
        v.selectStayRate(StayRateDto(id = "r1", totalAmount = "180.00")); idle()
        assertEquals("180.00" to "USD", v.stayTotal())
        v.bookStay(StayGuestInputDto(givenName = "Jane", familyName = "Doe"), "jane@example.com", "555"); idle()
        assertTrue(v.stayBooking.value is StayBookingState.Success, "stay=${v.stayBooking.value}")
        v.resetStayBooking()
    }

    @Test fun servicing_detailCancelChange() {
        repo.trips.value = listOf(BookedTrip(orderId = "ord_1", bookingReference = "ABC123", route = "SFO → JFK", departDate = "2027-06-24", amount = "250", currency = "USD", lastName = "Doe", tripId = "trip_1"))
        val v = vm()
        v.loadBookingDetail("ord_1"); idle()
        assertEquals("ABC123", v.orderDetail.value.order?.bookingReference)
        v.quoteCancellation("ord_1"); idle()
        assertNotNull(v.cancellation.value.quote)
        v.confirmCancellation("ord_1"); idle()
        v.requestChange("ord_1", "slice_1", "SFO", "JFK", "2027-07-01", "economy"); idle()
        assertEquals(1, v.change.value.offers.size)
        v.priceChange("ord_1", "chg_1"); idle()
        assertNotNull(v.change.value.price)
        v.confirmChange("ord_1", "chg_1"); idle()
    }
}
