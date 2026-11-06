package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.domain.isTripAwaitingPayment
import com.vayunmathur.travel.domain.isTripPollingStatus
import com.vayunmathur.travel.domain.tripStatusDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Trip-status domain mapping ([isTripPollingStatus], [isTripAwaitingPayment],
 * [tripStatusDisplay]) plus the [BookedTrip] entity defaults those predicates
 * read. TripsPage itself stays bound to the ViewModel/back stack (covered by
 * the on-device journey tests); what is asserted here is every branch the card
 * renders from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelTripStatusTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun pollingStatuses() {
        assertTrue(isTripPollingStatus("pending_payment"))
        assertTrue(isTripPollingStatus("processing"))
        assertFalse(isTripPollingStatus("confirmed"))
        assertFalse(isTripPollingStatus("cancelled"))
        assertFalse(isTripPollingStatus("failed"))
    }

    @Test
    fun awaitingPayment_onlyPending() {
        assertTrue(isTripAwaitingPayment("pending_payment"))
        assertFalse(isTripAwaitingPayment("processing"))
        assertFalse(isTripAwaitingPayment("confirmed"))
    }

    @Test
    fun statusDisplay_labels() {
        assertEquals("Payment pending", tripStatusDisplay("pending_payment"))
        assertEquals("Processing", tripStatusDisplay("processing"))
        assertEquals("Confirmed", tripStatusDisplay("confirmed"))
        assertEquals("Partial", tripStatusDisplay("partial"))
        assertEquals("Failed", tripStatusDisplay("failed"))
        assertEquals("Cancelled", tripStatusDisplay("cancelled"))
        // Unknown statuses surface capitalized, never blank.
        assertEquals("Custom", tripStatusDisplay("custom"))
        assertEquals("Unknown", tripStatusDisplay(""))
    }

    @Test
    fun bookedTrip_defaults() {
        val trip = BookedTrip(
            orderId = "ord_1", bookingReference = "K7QZ4M",
            route = "SFO → JFK", departDate = "2026-09-01",
            amount = "412.30", currency = "USD",
        )
        assertEquals("confirmed", trip.status)
        assertEquals("flight", trip.type)
        assertFalse(isTripPollingStatus(trip.status))
        val pending = trip.copy(status = "processing")
        assertTrue(isTripPollingStatus(pending.status))
    }

    @Test
    fun prettyDate_usedByTripCard() {
        // TripsPage renders departDate via prettyDate; pin the mapping here.
        assertEquals("Sep 1", com.vayunmathur.travel.util.TravelViewModel.prettyDate("2026-09-01"))
    }
}
