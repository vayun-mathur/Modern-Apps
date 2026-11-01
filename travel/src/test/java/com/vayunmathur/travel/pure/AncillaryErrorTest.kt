package com.vayunmathur.travel.pure

import com.vayunmathur.travel.domain.assignSeat
import com.vayunmathur.travel.domain.seatSelections
import com.vayunmathur.travel.network.PenaltyRuleDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.ServiceDto
import com.vayunmathur.travel.network.ServiceSelectionDto
import com.vayunmathur.travel.network.StayGuestInputDto
import com.vayunmathur.travel.network.StayLoyaltyAccountDto
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.StayRoomDto
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.network.travelExtractError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins ancillary/baggage service selection and error shaping: seat
 * selectability, baggage quantity rules, service tagging and the server error
 * extraction (`{"message": ...}`).
 *
 * travelExtractError, SeatElementDto and the stay DTOs are pure and
 * called directly; selection maps mirror the booking-ops rules
 * (non-positive quantities remove the service).
 */
class AncillaryErrorTest {
    private fun seat(
        designator: String,
        available: Boolean = true,
        serviceId: String? = "svc1",
        type: String = "seat",
    ): SeatElementDto = SeatElementDto(
        type = type, designator = designator, available = available,
        serviceId = serviceId, totalAmount = "10", totalCurrency = "USD",
    )

    private fun toggleSeat(selected: Map<String, SeatElementDto>, segment: String, s: SeatElementDto) =
        assignSeat(selected, segment, "pax_1", s)

    private fun setQuantity(selected: Map<String, Long>, id: String, qty: Long) =
        selected.toMutableMap().also { if (qty <= 0) it.remove(id) else it[id] = qty }

    @Test
    fun selectableSeatTogglesOnSegmentKey() {
        val selected = toggleSeat(emptyMap(), "seg1", seat("12A"))
        assertEquals(1, selected.size)
        assertTrue("seg1|pax_1" in selected)
        assertEquals(emptyMap(), toggleSeat(selected, "seg1", seat("12A")))
    }

    @Test
    fun unavailableOrServicelessSeatsAreIgnored() {
        assertEquals(emptyMap(), toggleSeat(emptyMap(), "seg1", seat("12A", available = false)))
        assertEquals(emptyMap(), toggleSeat(emptyMap(), "seg1", seat("12A", serviceId = null)))
    }

    @Test
    fun sameDesignatorOnTwoSegmentsAreDistinct() {
        val one = toggleSeat(emptyMap(), "seg1", seat("12A"))
        val two = toggleSeat(one, "seg2", seat("12A"))
        assertEquals(2, two.size)
    }

    @Test
    fun amenityElementsAreNotSeats() {
        assertTrue(seat("12A").isSeat)
        assertTrue(seat("12B", type = "restricted_seat_general").isSeat)
        assertFalse(seat("", type = "empty").isSeat)
        assertFalse(seat("", type = "lavatory").isSeat)
        assertFalse(seat("", type = "galley").isSeat)
    }

    @Test
    fun zeroQuantityRemovesBaggageService() {
        val added = setQuantity(emptyMap(), "bag1", 2)
        assertEquals(mapOf("bag1" to 2L), added)
        assertEquals(emptyMap(), setQuantity(added, "bag1", 0))
        assertEquals(emptyMap(), setQuantity(added, "bag1", -1))
    }

    @Test
    fun selectedSeatMapsToQuantityOne() {
        val selections = seatSelections(toggleSeat(emptyMap(), "seg1", seat("12A")))
        assertEquals(listOf(ServiceSelectionDto("svc1", 1, "pax_1")), selections)
    }

    @Test
    fun stayRoomCheapestRate() {
        val rooms = listOf(
            StayRoomDto("Standard", listOf(StayRateDto("r1", "RO", "200", "USD", "free"))),
            StayRoomDto("Deluxe", listOf(StayRateDto("r2", "BB", "300", "USD", "free"))),
        )
        val cheapest = rooms.flatMap { it.rates }.minByOrNull { it.totalAmount.toDoubleOrNull() ?: Double.MAX_VALUE }
        assertEquals("r1", cheapest?.id)
        assertEquals("200", cheapest?.totalAmount)
    }

    @Test
    fun staySearchResultDefaults() {
        val r = StaySearchResultDto()
        assertEquals("", r.id)
        assertEquals("0", r.cheapestAmount)
        assertEquals("USD", r.cheapestCurrency)
    }

    @Test
    fun stayGuestCarriesLoyalty() {
        val g = StayGuestInputDto("Jo", "Smith", "1990-01-01", StayLoyaltyAccountDto("Hilton", "123"))
        assertEquals("Hilton", g.loyaltyProgrammeAccount?.programmeName)
        assertEquals("1990-01-01", g.bornOn)
    }

    @Test
    fun serverErrorReadsMessage() {
        val body = """{"message":"This fare is no longer available. Please search again."}"""
        assertEquals(
            "This fare is no longer available. Please search again.",
            travelExtractError(body)
        )
    }

    @Test
    fun legacyErrorsShapeFallsBackToGeneric() {
        val body = """{"errors":[{"title":"Invalid request","message":"origin is required"}]}"""
        assertEquals("Something went wrong. Please try again.", travelExtractError(body))
    }

    @Test
    fun plainTextBodyFallsBackToGeneric() {
        assertEquals("Something went wrong. Please try again.", travelExtractError("upstream 500: boom"))
    }

    @Test
    fun blankBodyFallsBackToGeneric() {
        assertEquals("Something went wrong. Please try again.", travelExtractError("   "))
    }

    @Test
    fun errorIsTruncatedToSnippetLength() {
        val body = """{"message":"${"x".repeat(500)}"}"""
        assertEquals(300, travelExtractError(body).length)
    }

    @Test
    fun penaltyDefaultsToDisallowed() {
        assertFalse(PenaltyRuleDto().allowed)
        assertEquals(ServiceDto(), ServiceDto("", "", "", "0", "USD", 1, emptyList(), emptyList()))
    }
}
