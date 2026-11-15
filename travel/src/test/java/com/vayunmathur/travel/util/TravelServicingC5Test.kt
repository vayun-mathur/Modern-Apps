package com.vayunmathur.travel.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.TravelRaw
import com.vayunmathur.travel.network.travelTransport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Servicing branches the happy-path suite does not reach: missing/blank-ref
 * trips, direct succeeded/failed confirmations, first-poll terminal states,
 * checkout-url change polling, and expired re-checkout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelServicingC5Test {

    private class FakeRepo(ctx: android.content.Context) : TravelRepository(ctx) {
        val trips = MutableStateFlow<List<BookedTrip>>(emptyList())
        override fun observeRecent(limit: Int): Flow<List<RecentSearch>> = MutableStateFlow(emptyList())
        override fun observeBookedTrips(): Flow<List<BookedTrip>> = trips
        override fun observeFrequentFlyers(): Flow<List<FrequentFlyer>> = MutableStateFlow(emptyList())
        override fun observeCustomers(): Flow<List<Customer>> = MutableStateFlow(emptyList())
        override suspend fun getAllFrequentFlyers(): List<FrequentFlyer> = emptyList()
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

    /** URL fragment -> response; tests swap [handler] to script each scenario. */
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

    private fun stored(
        orderId: String = "ord_1",
        ref: String = "ABC123",
        expires: String = "",
    ) = BookedTrip(
        orderId = orderId, bookingReference = ref, route = "SFO → JFK",
        departDate = "2027-06-24", amount = "250.00", currency = "USD",
        status = "pending_payment", tripId = "trip_1", lastName = "Doe",
        checkoutExpiresAt = expires,
    )

    private fun respond(map: Map<String, String>, code: Int = 200): (String) -> TravelRaw = { url ->
        map.entries.firstOrNull { url.contains(it.key) }
            ?.let { TravelRaw(code, it.value) }
            ?: TravelRaw(404, """{"message":"not found"}""")
    }

    @Test
    fun loadBookingDetail_missingTrip_reportsNotFound() {
        val v = vm()
        v.loadBookingDetail("nope"); idle()
        assertEquals("Trip not found.", v.orderDetail.value.error)
    }

    @Test
    fun loadBookingDetail_blankRef_refreshesTripAndReportsPending() {
        repo.trips.value = listOf(stored(orderId = "trip_1", ref = ""))
        handler = respond(mapOf("/trips/" to """{"tripId":"trip_1","status":"processing","totalAmount":"260.00","currency":"USD"}"""))
        val v = vm()
        v.loadBookingDetail("trip_1"); idle()
        assertEquals("Payment is still pending for this trip.", v.orderDetail.value.error)
        assertEquals("260.00", repo.trips.value.single().amount)
    }

    @Test
    fun loadBookingDetail_blankRefFailure_surfacesError() {
        repo.trips.value = listOf(stored(ref = ""))
        val v = vm()
        v.loadBookingDetail("ord_1"); idle()
        assertNotNull(v.orderDetail.value.error)
        assertNull(v.orderDetail.value.order)
    }

    @Test
    fun quoteAndChange_missingTrip_reportCant() {
        val v = vm()
        v.quoteCancellation("nope"); idle()
        assertEquals("This trip can't be cancelled.", v.cancellation.value.error)
        v.requestChange("nope", "sli_1", "SFO", "JFK", "2027-07-01", ""); idle()
        assertTrue(v.change.value.requested)
        assertEquals("This trip can't be changed.", v.change.value.error)
        v.priceChange("nope", "chg_1"); idle()
        assertEquals("This trip can't be changed.", v.change.value.error)
        v.confirmChange("nope", "chg_1"); idle()
        assertEquals("This trip can't be changed.", v.change.value.error)
    }

    @Test
    fun confirmCancellation_directSucceeded_marksTripCancelled() {
        repo.trips.value = listOf(stored())
        handler = { url ->
            when {
                url.contains("/confirm") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","status":"succeeded"}""")
                url.contains("/cancel") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","commitable":true,"status":"quoted"}""")
                else -> TravelRaw(404, """{"message":"not found"}""")
            }
        }
        val v = vm()
        v.quoteCancellation("ord_1"); idle()
        v.confirmCancellation("ord_1"); idle()
        assertTrue(v.cancellation.value.done)
        assertEquals("cancelled", repo.trips.value.single().status)
        v.resetCancellation()
        assertNull(v.cancellation.value.quote)
    }

    @Test
    fun confirmCancellation_directFailed_showsServerMessage() {
        repo.trips.value = listOf(stored())
        handler = { url ->
            when {
                url.contains("/confirm") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","status":"failed","message":"Window closed"}""")
                url.contains("/cancel") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","commitable":true,"status":"quoted"}""")
                else -> TravelRaw(404, """{"message":"not found"}""")
            }
        }
        val v = vm()
        v.quoteCancellation("ord_1"); idle()
        v.confirmCancellation("ord_1"); idle()
        assertEquals("Window closed", v.cancellation.value.error)
    }

    @Test
    fun confirmCancellation_processingThenFirstPollSucceeds() {
        repo.trips.value = listOf(stored())
        var confirmCalls = 0
        handler = { url ->
            when {
                url.contains("/cancel") && !url.contains("/cancellations/") ->
                    TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","commitable":true,"status":"quoted"}""")
                url.contains("/confirm") -> {
                    confirmCalls++
                    TravelRaw(200, """{"id":"can_1","orderId":"ord_1","status":"processing"}""")
                }
                url.contains("/cancellations/") ->
                    TravelRaw(200, """{"id":"can_1","orderId":"ord_1","status":"succeeded"}""")
                else -> TravelRaw(404, """{"message":"not found"}""")
            }
        }
        val v = vm()
        v.quoteCancellation("ord_1"); idle()
        v.confirmCancellation("ord_1"); idle()
        assertEquals(1, confirmCalls)
        assertTrue(v.cancellation.value.done, "state=${v.cancellation.value}")
        assertEquals("cancelled", repo.trips.value.single().status)
    }

    @Test
    fun confirmCancellation_processingThenFirstPollFails() {
        repo.trips.value = listOf(stored())
        handler = { url ->
            when {
                url.contains("/cancel") && !url.contains("/cancellations/") ->
                    TravelRaw(200, """{"id":"can_1","orderId":"ord_1","refundAmount":"200.00","refundCurrency":"USD","commitable":true,"status":"quoted"}""")
                url.contains("/confirm") -> TravelRaw(200, """{"id":"can_1","orderId":"ord_1","status":"processing"}""")
                url.contains("/cancellations/") ->
                    TravelRaw(200, """{"id":"can_1","orderId":"ord_1","status":"failed","message":"Refund rejected"}""")
                else -> TravelRaw(404, """{"message":"not found"}""")
            }
        }
        val v = vm()
        v.quoteCancellation("ord_1"); idle()
        v.confirmCancellation("ord_1"); idle()
        assertEquals("Refund rejected", v.cancellation.value.error)
    }

    @Test
    fun confirmChange_withCheckoutUrl_pollsToConfirmed() {
        repo.trips.value = listOf(stored())
        handler = respond(mapOf(
            "/changes/status" to """{"orderId":"ord_1","status":"confirmed"}""",
            "/confirm" to """{"orderId":"ord_1","status":"processing","checkoutUrl":"https://pay.example/chg"}""",
        ))
        val v = vm()
        v.confirmChange("ord_1", "chg_1"); idle()
        assertTrue(v.change.value.done, "state=${v.change.value}")
        assertEquals("https://pay.example/chg", v.change.value.checkoutUrl)
        v.resetChange()
        assertTrue(v.change.value.offers.isEmpty())
    }

    @Test
    fun confirmChange_withoutUrl_appliesPricedFare() {
        repo.trips.value = listOf(stored())
        handler = respond(mapOf(
            "/price" to """{"outcome":"pay","amount":"50.00","currency":"USD","newFareAmount":"300.00"}""",
            "/confirm" to """{"orderId":"ord_1","status":"confirmed"}""",
        ))
        val v = vm()
        v.priceChange("ord_1", "chg_1"); idle()
        assertEquals("300.00", v.change.value.price?.newFareAmount)
        v.confirmChange("ord_1", "chg_1"); idle()
        assertTrue(v.change.value.done)
        assertEquals("300.00", repo.trips.value.single().amount)
    }

    /** Collects [TravelViewModel.bookedTrips] so its WhileSubscribed stateIn emits. */
    private fun subscribeTrips(v: TravelViewModel): kotlinx.coroutines.Job {
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            .launch { v.bookedTrips.collect {} }
        idle()
        return job
    }

    @Test
    fun recheckout_expired_reportsErrorWithoutNetwork() {
        repo.trips.value = listOf(stored(expires = "2000-01-01T00:00:00Z"))
        val v = vm()
        val job = subscribeTrips(v)
        try {
            assertEquals(1, v.bookedTrips.value.size)
            v.recheckoutForStoredTrip("ord_1"); idle()
            val error = v.checkout.value as CheckoutState.Error
            assertTrue(error.message.contains("expired"), "error=$error")
        } finally { job.cancel() }
    }

    @Test
    fun recheckout_unexpired_fetchesFreshIntent() {
        repo.trips.value = listOf(stored(expires = "2999-01-01T00:00:00Z"))
        handler = respond(mapOf(
            "/checkout" to """{"clientSecret":"cs_new","publishableKey":"pk_new","totalAmount":"255.00","currency":"USD","expiresAt":"2999-01-02T00:00:00Z"}""",
        ))
        val v = vm()
        val job = subscribeTrips(v)
        try {
            assertEquals(1, v.bookedTrips.value.size)
            v.recheckoutForStoredTrip("ord_1"); idle()
            val ready = v.checkout.value as CheckoutState.Ready
            assertEquals("255.00", ready.checkout.totalAmount)
            assertEquals("cs_new", ready.checkout.clientSecret)
            assertEquals("255.00", repo.trips.value.single().amount)
        } finally { job.cancel() }
    }

    @Test
    fun refreshBookingDetail_reloadsOrder() {
        repo.trips.value = listOf(stored())
        handler = respond(mapOf(
            "/bookings/" to """{"orderId":"ord_1","bookingReference":"ABC123","totalAmount":"250.00","currency":"USD","status":"confirmed","canCancel":true}""",
        ))
        val v = vm()
        v.loadBookingDetail("ord_1"); idle()
        assertEquals("ABC123", v.orderDetail.value.order?.bookingReference)
        v.refreshBookingDetail("ord_1"); idle()
        assertEquals("ABC123", v.orderDetail.value.order?.bookingReference)
    }
}
