package com.vayunmathur.travel.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.network.TravelRaw
import com.vayunmathur.travel.network.TripDto
import com.vayunmathur.travel.network.travelTransport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Booking branches beyond the happy path: missing-details checkout, seat-map
 * retry, stored-trip refresh, pending-trip ids, ancillary save failure, and
 * frequent-flyer prefill on passenger init.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelBookingC5Test {

    private class FakeRepo(ctx: android.content.Context) : TravelRepository(ctx) {
        val trips = MutableStateFlow<List<BookedTrip>>(emptyList())
        var flyers: List<FrequentFlyer> = emptyList()
        override fun observeRecent(limit: Int): Flow<List<RecentSearch>> = MutableStateFlow(emptyList())
        override fun observeBookedTrips(): Flow<List<BookedTrip>> = trips
        override fun observeFrequentFlyers(): Flow<List<FrequentFlyer>> = MutableStateFlow(emptyList())
        override fun observeCustomers(): Flow<List<Customer>> = MutableStateFlow(emptyList())
        override suspend fun getAllFrequentFlyers(): List<FrequentFlyer> = flyers
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
    private var handler: (String) -> TravelRaw = { TravelRaw(404, """{"message":"not found"}""") }

    @BeforeTest
    fun open() {
        repo = FakeRepo(ApplicationProvider.getApplicationContext())
        handler = { TravelRaw(404, """{"message":"not found"}""") }
        travelTransport = { _, url, _ -> handler(url) }
    }

    @AfterTest fun clear() { travelTransport = null }

    private fun vm(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, repo)
    }

    private fun idle() { repeat(5) { ShadowLooper.idleMainLooper() } }

    private fun offer() = OfferDto(
        offerId = "off_1", totalAmount = "250.00", currency = "USD", ownerIata = "AS",
        passengerIds = listOf("pax_1"),
        passengers = listOf(OfferPassengerDto(id = "pax_1", type = "adult")),
        slices = listOf(
            SliceDto(
                origin = "SFO", destination = "JFK",
                departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00",
                segments = listOf(
                    SegmentDto(
                        id = "seg_1", carrierIata = "AS", flightNumber = "100",
                        origin = "SFO", destination = "JFK",
                        departureAt = "2027-06-24T07:15:00", arrivalAt = "2027-06-24T15:50:00",
                    )
                ),
            )
        ),
    )

    @Test
    fun initPassengers_prefillsFrequentFlyerLoyalty() {
        repo.flyers = listOf(FrequentFlyer(airlineIata = "AS", accountNumber = "FF123", airlineName = "Alaska"))
        val v = vm()
        v.initPassengers(offer()); idle()
        assertEquals(1, v.passengers.value.size)
        assertEquals("FF123", v.passengers.value[0].loyaltyProgrammeAccounts.single().accountNumber)
        // Re-init with the same ids keeps the entered values.
        v.updatePassenger(0, v.passengers.value[0].copy(givenName = "Jane"))
        v.initPassengers(offer()); idle()
        assertEquals("Jane", v.passengers.value[0].givenName)
    }

    @Test
    fun prefillFromProfile_fillsBlanksOnly() {
        val v = vm()
        v.initPassengers(offer()); idle()
        v.updatePassenger(0, v.passengers.value[0].copy(givenName = "Typed"))
        v.prefillFromProfile("jane@example.com", "Jane", "Doe", "555-1")
        val lead = v.passengers.value[0]
        assertEquals("Typed", lead.givenName)
        assertEquals("Doe", lead.familyName)
        assertEquals("jane@example.com", lead.email)
        assertEquals("555-1", lead.phoneNumber)
    }

    @Test
    fun createTrip_missingDetails_routesBackWithFields() {
        handler = { url ->
            if (url.contains("/trips")) TravelRaw(422, """{"message":"Missing passport","missingFields":["passport"]}""")
            else TravelRaw(404, """{"message":"not found"}""")
        }
        val v = vm()
        v.selectOffer(offer()); v.initPassengers(offer()); idle()
        v.createTrip(); idle()
        val missing = v.booking.value as BookingState.MissingDetails
        assertEquals(listOf("passport"), missing.missingFields)
        v.resetBooking()
        assertTrue(v.booking.value is BookingState.Idle)
    }

    @Test
    fun createTrip_emptySelection_isNoOp() {
        val v = vm()
        v.createTrip(); idle()
        assertTrue(v.booking.value is BookingState.Idle)
    }

    @Test
    fun startCheckout_missingDetails_setsBookingState() {
        handler = { url ->
            if (url.contains("/checkout")) TravelRaw(422, """{"message":"Missing DOB","missingFields":["bornOn"]}""")
            else TravelRaw(404, """{"message":"not found"}""")
        }
        val v = vm()
        v.currentTrip = TripDto(tripId = "trip_1", status = "created")
        v.startCheckout(); idle()
        assertTrue(v.booking.value is BookingState.MissingDetails, "booking=${v.booking.value}")
        v.resetCheckout()
        assertTrue(v.checkout.value is CheckoutState.Idle)
    }

    @Test
    fun acceptNewPrice_onlyAppliesToReadyCheckout() {
        val v = vm()
        v.acceptNewPrice() // Idle: no-op, must not throw or change state.
        assertTrue(v.checkout.value is CheckoutState.Idle)
        v.checkoutMutable.value = CheckoutState.Ready(
            com.vayunmathur.travel.network.CheckoutDto(totalAmount = "300.00", priceChanged = true)
        )
        v.acceptNewPrice()
        assertTrue((v.checkout.value as CheckoutState.Ready).acceptedNewPrice)
    }

    @Test
    fun loadAncillaries_error_surfacesMessage() {
        val v = vm()
        v.loadAncillaries("trip_1"); idle()
        assertNotNull(v.ancillaries.value.error)
    }

    @Test
    fun retrySeatMaps_success_updatesAncillariesAndCabins() {
        handler = { url ->
            if (url.contains("/ancillaries")) {
                TravelRaw(
                    200,
                    """{"status":"ok","services":[],"seatMaps":[{"cabin":"economy","rows":[]}],"seatMapsPending":false}""",
                )
            } else TravelRaw(404, """{"message":"not found"}""")
        }
        val v = vm()
        v.currentTrip = TripDto(tripId = "trip_1", status = "created")
        v.retrySeatMaps(); idle()
        assertEquals(1, v.seatMap.value.cabins.size)
        assertEquals(false, v.ancillaries.value.seatMapsLoading)
        v.loadSeatMaps()
        assertEquals(1, v.seatMap.value.cabins.size)
    }

    @Test
    fun retrySeatMaps_failure_setsErrorAndNoTripIsNoOp() {
        val v = vm()
        v.retrySeatMaps(); idle() // No current trip: no-op.
        assertEquals(false, v.ancillaries.value.seatMapsLoading)
        v.currentTrip = TripDto(tripId = "trip_1", status = "created")
        v.retrySeatMaps(); idle()
        assertNotNull(v.ancillaries.value.error)
        assertEquals(false, v.ancillaries.value.seatMapsLoading)
    }

    @Test
    fun saveAncillaries_noChanges_proceedsWithoutNetwork() {
        val v = vm()
        v.currentTrip = TripDto(tripId = "trip_1", status = "created")
        var proceeded = false
        v.saveAncillaries { proceeded = true }
        assertTrue(proceeded)
        assertTrue(v.checkout.value is CheckoutState.Idle)
    }

    @Test
    fun saveAncillaries_failure_keepsError() {
        val v = vm()
        v.currentTrip = TripDto(tripId = "trip_1", status = "created")
        v.setBaggageQuantity("bag_1", 1)
        var proceeded = false
        v.saveAncillaries { proceeded = true }; idle()
        assertEquals(false, proceeded)
        assertNotNull(v.ancillaries.value.error)
        v.setBaggageQuantity("bag_1", 0)
        assertTrue(v.selectedBaggage.value.isEmpty())
        v.setExtraQuantity("ext_1", 2)
        assertEquals(2L, v.selectedExtras.value["ext_1"])
        v.setExtraQuantity("ext_1", 0)
        assertTrue(v.selectedExtras.value.isEmpty())
    }

    @Test
    fun toggleSeat_andSelectedServices_carryPassenger() {
        val v = vm()
        v.ancillariesMutable.value = AncillariesState(
            ancillaries = com.vayunmathur.travel.network.TripAncillariesDto(
                services = listOf(
                    com.vayunmathur.travel.network.ServiceDto(
                        id = "bag_1", type = "baggage", title = "Bag",
                        passengerIds = listOf("pax_1"),
                    )
                ),
            )
        )
        v.setBaggageQuantity("bag_1", 1)
        v.toggleSeat("seg_1", "pax_1", SeatElementDto(type = "seat", designator = "14A", available = true, serviceId = "seat_svc_1"))
        val services = v.selectedServices()
        assertTrue(services.any { it.id == "bag_1" && it.passengerId == "pax_1" }, "services=$services")
        assertTrue(services.any { it.id == "seat_svc_1" && it.passengerId == "pax_1" }, "services=$services")
    }

    @Test
    fun pollTrip_terminalStatus_finishesAndUpdatesStored() {
        repo.trips.value = listOf(
            BookedTrip(
                orderId = "trip_1", bookingReference = "", route = "SFO → JFK",
                departDate = "2027-06-24", amount = "250.00", currency = "USD", tripId = "trip_1",
            )
        )
        handler = { url ->
            if (url.contains("/trips/")) {
                TravelRaw(200, """{"tripId":"trip_1","status":"confirmed","bookingReference":"ABC123","totalAmount":"250.00","currency":"USD"}""")
            } else TravelRaw(404, """{"message":"not found"}""")
        }
        val v = vm()
        v.pollTrip("trip_1"); idle()
        assertTrue(v.tripPoll.value is TripPollState.Done, "poll=${v.tripPoll.value}")
        assertEquals("ABC123", repo.trips.value.single().bookingReference)
        v.stopTripPoll()
    }

    @Test
    fun confirmPaidTrip_failure_setsPollError() {
        val v = vm()
        v.confirmPaidTrip("trip_1"); idle()
        assertTrue(v.tripPoll.value is TripPollState.Error, "poll=${v.tripPoll.value}")
    }

    @Test
    fun refreshStoredTrip_updatesAmountAndMissingIsNoOp() {
        repo.trips.value = listOf(
            BookedTrip(
                orderId = "trip_1", bookingReference = "ABC123", route = "SFO → JFK",
                departDate = "2027-06-24", amount = "250.00", currency = "USD", tripId = "trip_1",
            )
        )
        handler = { url ->
            if (url.contains("/trips/")) {
                TravelRaw(200, """{"tripId":"trip_1","status":"confirmed","bookingReference":"ABC123","totalAmount":"275.00","currency":"USD"}""")
            } else TravelRaw(404, """{"message":"not found"}""")
        }
        val v = vm()
        v.refreshStoredTrip("missing"); idle()
        v.refreshStoredTrip("trip_1"); idle()
        assertEquals("275.00", repo.trips.value.single().amount)
    }

    @Test
    fun pendingTripIds_filtersByStatusAndFallsBackToOrderId() = runTest {
        repo.trips.value = listOf(
            BookedTrip(orderId = "ord_a", bookingReference = "A", route = "X → Y", departDate = "2027-01-01", amount = "1", currency = "USD", status = "pending_payment", tripId = "trip_a"),
            BookedTrip(orderId = "ord_b", bookingReference = "B", route = "X → Y", departDate = "2027-01-01", amount = "1", currency = "USD", status = "processing", tripId = ""),
            BookedTrip(orderId = "ord_c", bookingReference = "C", route = "X → Y", departDate = "2027-01-01", amount = "1", currency = "USD", status = "confirmed", tripId = "trip_c"),
        )
        val v = vm()
        assertEquals(listOf("trip_a", "ord_b"), v.pendingTripIds())
        assertEquals("Jane", PassengerInputDto(givenName = "Jane").givenName)
    }
}
