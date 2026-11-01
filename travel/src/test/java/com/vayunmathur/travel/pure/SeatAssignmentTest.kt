package com.vayunmathur.travel.pure

import com.vayunmathur.travel.domain.ancillariesNeedSaving
import com.vayunmathur.travel.domain.assignSeat
import com.vayunmathur.travel.domain.nextPassengerToSeat
import com.vayunmathur.travel.domain.seatHolder
import com.vayunmathur.travel.domain.seatSelections
import com.vayunmathur.travel.domain.seatedPassengers
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.ServiceSelectionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeatAssignmentTest {
    private fun seat(d: String) = SeatElementDto(type = "seat", designator = d, available = true, serviceId = "svc_$d")

    private val passengers = listOf(
        OfferPassengerDto("pax_1", "adult"),
        OfferPassengerDto("pax_2", "adult"),
        OfferPassengerDto("pax_3", "infant_without_seat"),
    )

    @Test
    fun eachPassengerGetsOwnSeat() {
        val one = assignSeat(emptyMap(), "s1g1", "pax_1", seat("12A"))
        val two = assignSeat(one, "s1g1", "pax_2", seat("12B"))
        assertEquals("pax_1", seatHolder(two, "s1g1", "12A"))
        assertEquals("pax_2", seatHolder(two, "s1g1", "12B"))
        assertEquals(
            setOf(ServiceSelectionDto("svc_12A", 1, "pax_1"), ServiceSelectionDto("svc_12B", 1, "pax_2")),
            seatSelections(two).toSet(),
        )
    }

    @Test
    fun pickingAnotherSeatMovesThePassenger() {
        val one = assignSeat(emptyMap(), "s1g1", "pax_1", seat("12A"))
        val moved = assignSeat(one, "s1g1", "pax_1", seat("14C"))
        assertEquals(1, moved.size)
        assertNull(seatHolder(moved, "s1g1", "12A"))
        assertEquals("pax_1", seatHolder(moved, "s1g1", "14C"))
    }

    @Test
    fun seatHeldBySomeoneElseCantBeTaken_andOwnSeatToggles() {
        val one = assignSeat(emptyMap(), "s1g1", "pax_1", seat("12A"))
        assertEquals(one, assignSeat(one, "s1g1", "pax_2", seat("12A")))
        assertTrue(assignSeat(one, "s1g1", "pax_1", seat("12A")).isEmpty())
    }

    @Test
    fun segmentsAreIndependent() {
        val one = assignSeat(emptyMap(), "s1g1", "pax_1", seat("12A"))
        val two = assignSeat(one, "s2g1", "pax_1", seat("12A"))
        assertEquals(2, two.size)
    }

    @Test
    fun infantsOnLapAreNotSeated() {
        assertEquals(listOf("pax_1", "pax_2"), seatedPassengers(passengers).map { it.id })
        val one = assignSeat(emptyMap(), "s1g1", "pax_1", seat("12A"))
        assertEquals("pax_2", nextPassengerToSeat(one, "s1g1", passengers))
    }

    @Test
    fun clearingExtrasStillNeedsSaving() {
        val bag = ServiceSelectionDto("svc_bag#0", 1, "pax_1")
        assertFalse(ancillariesNeedSaving(emptyList(), emptyList()))
        assertTrue(ancillariesNeedSaving(listOf(bag), emptyList()))
        assertFalse(ancillariesNeedSaving(listOf(bag), listOf(bag)))
        assertTrue(ancillariesNeedSaving(emptyList(), listOf(bag)))
    }
}
