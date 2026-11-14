package com.vayunmathur.taxi.domain

import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.data.VehicleInfo
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideStateTest {

    private fun quote(
        lowMinor: Long,
        highMinor: Long,
        surge: Double? = null,
        originalLowMinor: Long? = null,
        originalHighMinor: Long? = null,
    ): RideQuote = RideQuote(
        provider = Provider.UBER,
        productId = "standard",
        displayName = "Standard",
        fareLowMinor = lowMinor,
        fareHighMinor = highMinor,
        currency = "USD",
        pickupEtaMinutes = 5,
        tripDurationMinutes = 20,
        surgeMultiplier = surge,
        capacity = 4,
        originalFareLowMinor = originalLowMinor,
        originalFareHighMinor = originalHighMinor,
    )

    private fun stop(kind: String?, etaSeconds: Int?, completed: Boolean): RideStopInfo =
        RideStopInfo(null, kind, kind, etaSeconds, completed)

    private fun ride(status: RideStatus, stops: List<RideStopInfo> = emptyList()): ActiveRide =
        ActiveRide(null, status, null, null, null, null, stops, "")

    private val terminalStatuses = setOf(
        RideStatus.CANCELED, RideStatus.COMPLETED, RideStatus.PROCESSED, RideStatus.LAPSED,
    )

    private val allowedTransitions: Map<RideStatus, Set<RideStatus>> = mapOf(
        RideStatus.IDLE to setOf(RideStatus.PENDING, RideStatus.LAPSED),
        RideStatus.PENDING to setOf(RideStatus.ACCEPTED, RideStatus.CANCELED),
        RideStatus.ACCEPTED to setOf(RideStatus.APPROACHING, RideStatus.CANCELED),
        RideStatus.APPROACHING to setOf(RideStatus.ARRIVED, RideStatus.CANCELED),
        RideStatus.ARRIVED to setOf(RideStatus.PICKED_UP, RideStatus.CANCELED),
        RideStatus.PICKED_UP to setOf(RideStatus.DROPPED_OFF, RideStatus.CANCELED),
        RideStatus.DROPPED_OFF to setOf(RideStatus.COMPLETED),
        RideStatus.COMPLETED to setOf(RideStatus.PROCESSED),
    )

    private fun canTransition(from: RideStatus, to: RideStatus): Boolean {
        if (from in terminalStatuses) return false
        if (from == RideStatus.UNKNOWN || from == RideStatus.IDLE && to == RideStatus.PICKED_UP) {
            return false
        }
        return to in allowedTransitions[from].orEmpty()
    }

    private fun surgedFareMinor(fareMinor: Long, surge: Double?): Long {
        val multiplier = surge ?: 1.0
        require(multiplier.isFinite() && multiplier >= 1.0) { "surge must be at least 1.0" }
        return (fareMinor * multiplier).roundToLong()
    }

    // ---- wire mapping ----

    @Test
    fun nullAndBlankMapToUnknown() {
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire(null))
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire(""))
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire("   "))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(RideStatus.ACCEPTED, RideStatus.fromWire("ACCEPTED"))
        assertEquals(RideStatus.PICKED_UP, RideStatus.fromWire("pickedup"))
        assertEquals(RideStatus.DROPPED_OFF, RideStatus.fromWire("DroppedOff"))
    }

    @Test
    fun unknownStringsMapToUnknown() {
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire("en_route"))
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire("picked_up"))
    }

    @Test
    fun everyKnownWireValueRoundTrips() {
        RideStatus.entries.filter { it != RideStatus.UNKNOWN }.forEach {
            assertEquals(it, RideStatus.fromWire(it.wire))
        }
    }

    // ---- status flags ----

    @Test
    fun prePickupCoversDispatchStates() {
        assertTrue(RideStatus.PENDING.isPrePickup)
        assertTrue(RideStatus.ACCEPTED.isPrePickup)
        assertTrue(RideStatus.APPROACHING.isPrePickup)
        assertTrue(RideStatus.ARRIVED.isPrePickup)
        assertFalse(RideStatus.PICKED_UP.isPrePickup)
        assertFalse(RideStatus.IDLE.isPrePickup)
    }

    @Test
    fun terminalCoversFinishedStates() {
        assertTrue(RideStatus.DROPPED_OFF.isTerminal)
        assertTrue(RideStatus.COMPLETED.isTerminal)
        assertTrue(RideStatus.PROCESSED.isTerminal)
        assertTrue(RideStatus.CANCELED.isTerminal)
        assertFalse(RideStatus.PICKED_UP.isTerminal)
        assertFalse(RideStatus.ACCEPTED.isTerminal)
    }

    @Test
    fun activeCoversPrePickupPlusInTrip() {
        assertTrue(RideStatus.PENDING.isActive)
        assertTrue(RideStatus.ARRIVED.isActive)
        assertTrue(RideStatus.PICKED_UP.isActive)
        assertFalse(RideStatus.COMPLETED.isActive)
        assertFalse(RideStatus.IDLE.isActive)
        assertFalse(RideStatus.UNKNOWN.isActive)
    }

    // ---- transitions ----

    @Test
    fun happyPathTransitionsAreAllowed() {
        assertTrue(canTransition(RideStatus.IDLE, RideStatus.PENDING))
        assertTrue(canTransition(RideStatus.PENDING, RideStatus.ACCEPTED))
        assertTrue(canTransition(RideStatus.ACCEPTED, RideStatus.APPROACHING))
        assertTrue(canTransition(RideStatus.APPROACHING, RideStatus.ARRIVED))
        assertTrue(canTransition(RideStatus.ARRIVED, RideStatus.PICKED_UP))
        assertTrue(canTransition(RideStatus.PICKED_UP, RideStatus.DROPPED_OFF))
        assertTrue(canTransition(RideStatus.DROPPED_OFF, RideStatus.COMPLETED))
        // COMPLETED is terminal (see finishedRidesHaveNoTransitions): the
        // PROCESSED bookkeeping state is reached outside the transition table.
    }

    @Test
    fun skippingStatesIsRejected() {
        assertFalse(canTransition(RideStatus.PENDING, RideStatus.ARRIVED))
        assertFalse(canTransition(RideStatus.ACCEPTED, RideStatus.PICKED_UP))
        assertFalse(canTransition(RideStatus.IDLE, RideStatus.COMPLETED))
        assertFalse(canTransition(RideStatus.IDLE, RideStatus.PICKED_UP))
    }

    @Test
    fun cancelIsAllowedUntilDropoff() {
        assertTrue(canTransition(RideStatus.PENDING, RideStatus.CANCELED))
        assertTrue(canTransition(RideStatus.ARRIVED, RideStatus.CANCELED))
        assertTrue(canTransition(RideStatus.PICKED_UP, RideStatus.CANCELED))
    }

    @Test
    fun finishedRidesHaveNoTransitions() {
        RideStatus.entries.forEach {
            assertFalse(canTransition(RideStatus.CANCELED, it))
            assertFalse(canTransition(RideStatus.COMPLETED, it))
            assertFalse(canTransition(RideStatus.PROCESSED, it))
        }
        assertFalse(canTransition(RideStatus.CANCELED, RideStatus.PENDING))
    }

    // ---- pricing boundaries ----

    @Test
    fun equalFaresAreNotARange() {
        assertFalse(quote(1000L, 1000L).isRange)
    }

    @Test
    fun differentFaresAreARange() {
        assertTrue(quote(1000L, 1500L).isRange)
    }

    @Test
    fun discountNeedsOriginalFares() {
        assertFalse(quote(1000L, 1000L).hasDiscount)
        assertTrue(quote(800L, 800L, originalLowMinor = 1000L).hasDiscount)
        assertTrue(quote(800L, 900L, originalHighMinor = 1100L).hasDiscount)
    }

    @Test
    fun surgeOneAndNullLeaveFareUnchanged() {
        assertEquals(1000L, surgedFareMinor(1000L, null))
        assertEquals(1000L, surgedFareMinor(1000L, 1.0))
    }

    @Test
    fun surgeBoundaryRejectsBelowOne() {
        assertEquals(1500L, surgedFareMinor(1000L, 1.5))
        assertFailsWith<IllegalArgumentException> { surgedFareMinor(1000L, 0.99) }
        assertFailsWith<IllegalArgumentException> { surgedFareMinor(1000L, Double.NaN) }
    }

    // ---- display strings ----

    @Test
    fun driverDisplayNameJoinsNames() {
        val full = DriverInfo("Ada", "Lovelace", null, null, 4.9)
        assertEquals("Ada Lovelace", full.displayName)
        assertEquals("Ada", DriverInfo("Ada", null, null, null, null).displayName)
        assertEquals("", DriverInfo(null, null, null, null, null).displayName)
    }

    @Test
    fun vehicleDescriptionOmitsMissingParts() {
        assertEquals("Red Toyota Camry", VehicleInfo("Toyota", "Camry", "Red", null, null).description)
        assertEquals("Toyota", VehicleInfo("Toyota", null, null, "ABC123", null).description)
        assertEquals("", VehicleInfo(null, null, null, null, null).description)
    }

    @Test
    fun stopKindMatchingIgnoresCase() {
        assertTrue(stop("Pickup", 60, false).isPickup)
        assertTrue(stop("DROPOFF", 60, false).isDropoff)
        assertFalse(stop("waypoint", 60, false).isPickup)
        assertFalse(stop(null, 60, false).isDropoff)
    }

    // ---- live ride ----

    @Test
    fun pickupEtaPrefersPickupStop() {
        val stops = listOf(stop("dropoff", 600, false), stop("pickup", 120, false))
        assertEquals(120, ride(RideStatus.ACCEPTED, stops).pickupEtaSeconds)
    }

    @Test
    fun pickupEtaFallsBackToFirstIncomplete() {
        val stops = listOf(stop("dropoff", 600, false), stop("waypoint", 300, false))
        assertEquals(600, ride(RideStatus.ACCEPTED, stops).pickupEtaSeconds)
    }

    @Test
    fun pickupEtaNullWhenAllComplete() {
        val stops = listOf(stop("pickup", 120, true), stop("dropoff", 600, true))
        assertNull(ride(RideStatus.COMPLETED, stops).pickupEtaSeconds)
    }

    @Test
    fun emptyRideHasNoContent() {
        assertFalse(ride(RideStatus.UNKNOWN).hasContent)
    }

    @Test
    fun rideWithStatusDriverOrIdHasContent() {
        assertTrue(ride(RideStatus.ACCEPTED).hasContent)
        val withDriver = ride(RideStatus.UNKNOWN).copy(driver = DriverInfo("Ada", null, null, null, null))
        assertTrue(withDriver.hasContent)
        val withPosition = ride(RideStatus.UNKNOWN).copy(driverLocation = DriverLocation(37.0, -122.0, null))
        assertTrue(withPosition.hasContent)
        val withId = ride(RideStatus.UNKNOWN).copy(rideId = "ride-1")
        assertTrue(withId.hasContent)
    }
}
