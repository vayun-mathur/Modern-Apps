package com.vayunmathur.travel.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure ViewModel state transitions (no network): passenger init/update/prefill,
 * baggage + extra quantities, sort/filter mutations, offer selection reset, seat
 * toggling, and the [TravelViewModel.prettyDate] / [TravelViewModel.errorMessage]
 * helpers. Networked ops (searchFlights, createTrip, checkout) are out of scope.
 *
 * The repository is faked in-memory: [TravelRepository]'s singleton is backed by
 * SQLCipher (needs AndroidKeyStore), so the ViewModel is driven against this
 * fake instead. Only the DAO-backed flows the ViewModel subscribes to are
 * implemented; the rest of the repository surface is unused by these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelViewModelStateTest {

    private class FakeTravelRepository(ctx: android.content.Context) :
        TravelRepository(ctx) {
        val recents = MutableStateFlow<List<RecentSearch>>(emptyList())
        val trips = MutableStateFlow<List<BookedTrip>>(emptyList())
        val flyers = MutableStateFlow<List<FrequentFlyer>>(emptyList())
        val customers = MutableStateFlow<List<Customer>>(emptyList())
        override fun observeRecent(limit: Int): Flow<List<RecentSearch>> = recents
        override fun observeBookedTrips(): Flow<List<BookedTrip>> = trips
        override fun observeFrequentFlyers(): Flow<List<FrequentFlyer>> = flyers
        override fun observeCustomers(): Flow<List<Customer>> = customers
        override suspend fun getAllFrequentFlyers(): List<FrequentFlyer> = flyers.value
    }

    private lateinit var repository: FakeTravelRepository

    @BeforeTest
    fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        repository = FakeTravelRepository(ctx)
    }

    private fun viewModel(): TravelViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return TravelViewModel(ctx as android.app.Application, repository)
    }

    private fun offer(vararg paxIds: String) = OfferDto(
        offerId = "off_1", itineraryId = "itin_1", totalAmount = "100.00",
        passengerIds = paxIds.toList(),
        slices = listOf(
            SliceDto(
                origin = "SFO", destination = "JFK",
                departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
                segments = listOf(SegmentDto(carrierIata = "AS")),
            ),
        ),
    )

    @Test
    fun initPassengers_createsBlanksPerId() {
        val vm = viewModel()
        vm.initPassengers(offer("pax_1", "pax_2"))
        // initPassengers launches a coroutine for loyalty prefill; the blanks are synchronous.
        assertEquals(listOf("pax_1", "pax_2"), vm.passengers.value.map { it.id })
    }

    @Test
    fun initPassengers_keepsExistingValues() {
        val vm = viewModel()
        vm.initPassengers(offer("pax_1"))
        vm.updatePassenger(0, PassengerInputDto(id = "pax_1", givenName = "Jane"))
        vm.initPassengers(offer("pax_1"))
        assertEquals("Jane", vm.passengers.value[0].givenName)
    }

    @Test
    fun updatePassenger_ignoresOutOfRange() {
        val vm = viewModel()
        vm.initPassengers(offer("pax_1"))
        vm.updatePassenger(5, PassengerInputDto(id = "x"))
        assertEquals(1, vm.passengers.value.size)
    }

    @Test
    fun prefillFromProfile_fillsBlanksOnly() {
        val vm = viewModel()
        vm.initPassengers(offer("pax_1"))
        vm.updatePassenger(0, PassengerInputDto(id = "pax_1", givenName = "Typed"))
        vm.prefillFromProfile("a@b.c", "Profile", "Name", "+10000000000")
        val lead = vm.passengers.value[0]
        assertEquals("Typed", lead.givenName)
        assertEquals("Name", lead.familyName)
        assertEquals("a@b.c", lead.email)
    }

    @Test
    fun baggageQuantities_addAndRemove() {
        val vm = viewModel()
        vm.setBaggageQuantity("bag_1", 2)
        assertEquals(2L, vm.selectedBaggage.value["bag_1"])
        vm.setBaggageQuantity("bag_1", 0)
        assertFalse(vm.selectedBaggage.value.containsKey("bag_1"))
    }

    @Test
    fun extraQuantities_addAndRemove() {
        val vm = viewModel()
        vm.setExtraQuantity("cfar", 1)
        assertEquals(1L, vm.selectedExtras.value["cfar"])
        vm.setExtraQuantity("cfar", -1)
        assertFalse(vm.selectedExtras.value.containsKey("cfar"))
    }

    @Test
    fun sortAndFilters_mutateState() {
        val vm = viewModel()
        vm.setSort(OfferSort.CHEAPEST)
        assertEquals(OfferSort.CHEAPEST, vm.flights.value.sort)
        vm.setMaxStopsFilter(0)
        assertEquals(0, vm.flights.value.filters.maxStops)
        vm.toggleAirlineFilter("AS")
        assertTrue("AS" in vm.flights.value.filters.airlines)
        vm.toggleAirlineFilter("AS")
        assertFalse("AS" in vm.flights.value.filters.airlines)
        vm.setFareBrandFilter("Main")
        assertEquals("Main", vm.flights.value.filters.fareBrand)
        vm.clearFilters()
        assertEquals(FlightFilters(), vm.flights.value.filters)
    }

    @Test
    fun selectOffer_resetsFlow() {
        val vm = viewModel()
        vm.setBaggageQuantity("bag_1", 1)
        vm.toggleSeat("seg1", "pax_1", SeatElementDto(type = "seat", designator = "12A", available = true, serviceId = "svc_12A"))
        assertEquals(1, vm.selectedSeats.value.size)
        vm.selectOffer(offer("pax_1"))
        assertEquals(listOf("off_1"), vm.selectedOfferIds.value)
        assertTrue(vm.selectedBaggage.value.isEmpty())
        assertTrue(vm.selectedSeats.value.isEmpty())
        assertEquals("off_1", vm.review.value.offer?.offerId)
    }

    @Test
    fun toggleSeat_assignsAndClears() {
        val vm = viewModel()
        val seat = SeatElementDto(type = "seat", designator = "12A", available = true, serviceId = "svc_12A")
        vm.toggleSeat("seg1", "pax_1", seat)
        assertEquals(seat, vm.selectedSeats.value["seg1|pax_1"])
        // Tapping the same seat again clears it.
        vm.toggleSeat("seg1", "pax_1", seat)
        assertTrue(vm.selectedSeats.value.isEmpty())
    }

    @Test
    fun prettyDate_formats() {
        assertEquals("Sep 1", TravelViewModel.prettyDate("2026-09-01"))
        assertEquals("oops", TravelViewModel.prettyDate("oops"))
    }

    @Test
    fun resetBooking_returnsToIdle() {
        val vm = viewModel()
        vm.resetBooking()
        assertTrue(vm.booking.value is BookingState.Idle)
    }

    @Test
    fun errorMessage_prefersThrowableText() {
        val vm = viewModel()
        assertEquals("nope", vm.errorMessage(RuntimeException("nope")))
        assertEquals("Something went wrong. Please try again.", vm.errorMessage(RuntimeException()))
    }
}
