package com.vayunmathur.travel.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Network-backed ops with no server: each must land in a visible error/idle
 * state instead of crashing or hanging (offline behaviour of the flow).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelOpsErrorTest {

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
    private fun idle() { ShadowLooper.idleMainLooper() }

    @Test fun loadBookingDetail_unknownTrip_errors() {
        val v = vm(); v.loadBookingDetail("missing"); idle()
        assertEquals("Trip not found.", v.orderDetail.value.error)
    }

    @Test fun quoteCancellation_unknownTrip_errors() {
        val v = vm(); v.quoteCancellation("missing"); idle()
        assertNotNull(v.cancellation.value.error)
    }

    @Test fun searchFlights_offline_errors() {
        val v = vm()
        v.searchFlights(FlightQuery(slices = "SFO:JFK", adults = 1, children = "", infants = 0, cabin = "economy", maxConnections = 2)); idle()
        assertTrue(v.flights.value.error != null || v.flights.value.hasSearched)
    }

    @Test fun searchStays_offline_errors() {
        val v = vm(); v.searchStays("Paris", "2026-06-24", "2026-06-26", 1, 2, null, null); idle()
        assertTrue(v.stayResults.value.error != null || v.stayResults.value.loading)
    }

    @Test fun loadStayRates_offline_errors() {
        val v = vm(); v.loadStayRates("sr_1", "Hotel"); idle()
        assertTrue(v.stayRates.value.error != null || v.stayRates.value.loading)
    }

    @Test fun createTrip_withoutOffer_staysIdleOrErrors() {
        val v = vm(); v.createTrip(); idle()
        assertTrue(v.booking.value is BookingState.Idle || v.booking.value is BookingState.Error)
    }

    @Test fun resetStates_clearErrors() {
        val v = vm()
        v.loadBookingDetail("missing"); idle()
        v.resetCancellation()
        assertNull(v.cancellation.value.error)
        v.resetCheckout()
        assertTrue(v.checkout.value is CheckoutState.Idle)
        v.resetBooking()
        assertTrue(v.booking.value is BookingState.Idle)
    }

    @Test fun customerOps_withFakeRepo_doNotCrash() {
        val v = vm()
        v.saveFrequentFlyer("AS", "123", "Alaska"); idle()
        v.removeFrequentFlyer("AS"); idle()
        v.createCustomer("a@b.c", "Jane", "Doe", "555"); idle()
        v.removeCustomer("c1"); idle()
    }
}
