package com.vayunmathur.travel.pure

import com.vayunmathur.travel.domain.faresForItineraryPair
import com.vayunmathur.travel.domain.filterOffers
import com.vayunmathur.travel.domain.groupInboundSlices
import com.vayunmathur.travel.domain.groupOutboundSlices
import com.vayunmathur.travel.domain.itinerarySignature
import com.vayunmathur.travel.domain.roundTripOffers
import com.vayunmathur.travel.domain.sliceSignature
import com.vayunmathur.travel.domain.sortOffers
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.platform.FlightFilters
import com.vayunmathur.travel.platform.OfferSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the on-device offer sort/filter + round-trip partial grouping: the
 * server returns every offer and the app groups/sorts locally.
 */
class OfferGroupingTest {

    private fun seg(
        carrier: String = "BA",
        flight: String = "249",
        origin: String = "LHR",
        destination: String = "JFK",
        depart: String = "2026-09-01T10:00:00",
        arrive: String = "2026-09-01T13:00:00",
    ) = SegmentDto(
        carrierIata = carrier,
        flightNumber = flight,
        origin = origin,
        destination = destination,
        departureAt = depart,
        arrivalAt = arrive,
    )

    private fun slice(
        origin: String = "LHR",
        destination: String = "JFK",
        depart: String = "2026-09-01T10:00:00",
        arrive: String = "2026-09-01T13:00:00",
        stops: Long = 0,
        duration: Long = 180,
        vararg segs: SegmentDto = arrayOf(seg(origin = origin, destination = destination, depart = depart, arrive = arrive)),
    ) = SliceDto(
        origin = origin,
        destination = destination,
        departureAt = depart,
        arrivalAt = arrive,
        durationMinutes = duration,
        stops = stops,
        segments = segs.toList(),
    )

    private fun offer(
        id: String,
        amount: String,
        duration: Long = 180,
        fareBrand: String = "Main",
        slices: List<SliceDto> = listOf(slice()),
    ) = OfferDto(
        offerId = id,
        itineraryId = "itin_$id",
        totalAmount = amount,
        totalDurationMinutes = duration,
        fareBrand = fareBrand,
        slices = slices,
    )

    private fun roundTrip(
        id: String,
        amount: String,
        outDepart: String = "2026-09-01T10:00:00",
        inDepart: String = "2026-09-08T10:00:00",
        fareBrand: String = "Main",
    ) = offer(
        id, amount, fareBrand = fareBrand,
        slices = listOf(
            slice(depart = outDepart, arrive = "2026-09-01T13:00:00"),
            slice(origin = "JFK", destination = "LHR", depart = inDepart, arrive = "2026-09-08T22:00:00"),
        ),
    )

    // --- sort ---

    @Test
    fun cheapestSortOrdersByAmount() {
        val offers = listOf(offer("b", "300"), offer("a", "100"), offer("c", "200"))
        assertEquals(listOf("a", "c", "b"), sortOffers(offers, OfferSort.CHEAPEST).map { it.offerId })
    }

    @Test
    fun fastestSortOrdersByDuration() {
        val offers = listOf(offer("a", "100", duration = 300), offer("b", "300", duration = 120))
        assertEquals(listOf("b", "a"), sortOffers(offers, OfferSort.FASTEST).map { it.offerId })
    }

    @Test
    fun bestSortKeepsServerOrder() {
        val offers = listOf(offer("b", "300"), offer("a", "100"))
        assertEquals(listOf("b", "a"), sortOffers(offers, OfferSort.BEST).map { it.offerId })
    }

    // --- filter ---

    @Test
    fun maxStopsFilterDropsConnections() {
        val nonstop = offer("a", "100", slices = listOf(slice(stops = 0)))
        val onestop = offer(
            "b", "80",
            slices = listOf(
                slice(
                    stops = 1, duration = 300,
                    segs = arrayOf(
                        seg(destination = "DUB"),
                        seg(origin = "DUB", flight = "117"),
                    )
                )
            )
        )
        val filtered = filterOffers(listOf(nonstop, onestop), FlightFilters(maxStops = 0))
        assertEquals(listOf("a"), filtered.map { it.offerId })
    }

    @Test
    fun airlineFilterMatchesAnySliceCarrier() {
        val ba = offer("a", "100")
        val af = offer(
            "b", "90",
            slices = listOf(
                slice(
                    segs = arrayOf(seg(carrier = "AF", flight = "1681"))
                )
            )
        )
        assertEquals(listOf("b"), filterOffers(listOf(ba, af), FlightFilters(airlines = setOf("AF"))).map { it.offerId })
    }

    @Test
    fun fareBrandFilterMatchesBrand() {
        val offers = listOf(offer("a", "100", fareBrand = "Main"), offer("b", "60", fareBrand = "Basic"))
        assertEquals(listOf("b"), filterOffers(offers, FlightFilters(fareBrand = "Basic")).map { it.offerId })
    }

    // --- partial grouping ---

    @Test
    fun oneWayOffersExcludedFromRoundTripGrouping() {
        val oneWay = offer("solo", "50")
        val rt = roundTrip("rt", "400")
        assertEquals(listOf(rt), roundTripOffers(listOf(oneWay, rt)))
        assertEquals(1, groupOutboundSlices(listOf(oneWay, rt)).size)
    }

    @Test
    fun outboundGroupsByDistinctSliceCheapestFirst() {
        val cheapEarly = roundTrip("early-cheap", "300", outDepart = "2026-09-01T08:00:00")
        val priceyEarly = roundTrip("early-pricey", "500", outDepart = "2026-09-01T08:00:00")
        val late = roundTrip("late", "250", outDepart = "2026-09-01T18:00:00")
        val grouped = groupOutboundSlices(listOf(priceyEarly, late, cheapEarly))
        // Two distinct outbounds; each row is its cheapest fare; cheapest row first.
        assertEquals(2, grouped.size)
        assertEquals("late", grouped[0].offerId)
        assertEquals("early-cheap", grouped[1].offerId)
    }

    @Test
    fun inboundGroupsWithinChosenOutbound() {
        val out = "2026-09-01T10:00:00"
        val a = roundTrip("a", "400", outDepart = out, inDepart = "2026-09-08T10:00:00")
        val b = roundTrip("b", "350", outDepart = out, inDepart = "2026-09-08T18:00:00")
        val other = roundTrip("c", "200", outDepart = "2026-09-01T18:00:00", inDepart = "2026-09-08T10:00:00")
        val signature = sliceSignature(a.slices[0])
        val grouped = groupInboundSlices(listOf(a, b, other), signature)
        assertEquals(listOf("b", "a"), grouped.map { it.offerId })
    }

    @Test
    fun faresListEveryFareForPairCheapestFirst() {
        val out = "2026-09-01T10:00:00"
        val inbound = "2026-09-08T10:00:00"
        val main = roundTrip("main", "400", outDepart = out, inDepart = inbound, fareBrand = "Main")
        val basic = roundTrip("basic", "320", outDepart = out, inDepart = inbound, fareBrand = "Basic")
        val fares = faresForItineraryPair(
            listOf(main, basic),
            sliceSignature(main.slices[0]),
            sliceSignature(main.slices[1]),
        )
        assertEquals(listOf("basic", "main"), fares.map { it.offerId })
    }

    @Test
    fun sameFlightsDifferentFaresShareSignature() {
        val a = roundTrip("a", "400")
        val b = roundTrip("b", "450")
        assertEquals(itinerarySignature(a), itinerarySignature(b))
        assertTrue(sliceSignature(a.slices[0]).isNotBlank())
    }
}
