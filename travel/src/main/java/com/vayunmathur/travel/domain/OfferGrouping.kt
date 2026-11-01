package com.vayunmathur.travel.domain

import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.FlightFilters
import com.vayunmathur.travel.util.OfferSort

/**
 * On-device offer sort + filter (the server returns every offer cheapest
 * first; sorting/filtering happens locally). Pure: covered by unit tests.
 */

/** Filter [offers] to the visible set for [filters]. */
fun filterOffers(offers: List<OfferDto>, filters: FlightFilters): List<OfferDto> =
    offers.filter { offer ->
        (filters.maxStops == null || offer.slices.all { it.stops <= filters.maxStops }) &&
            (filters.airlines.isEmpty() || offer.airlineIatas.any { it in filters.airlines }) &&
            (filters.fareBrand == null || offer.fareBrand == filters.fareBrand)
    }

/** Sort [offers] per [sort]: cheapest by amount, fastest by duration, else as-is. */
fun sortOffers(offers: List<OfferDto>, sort: OfferSort): List<OfferDto> = when (sort) {
    OfferSort.CHEAPEST -> offers.sortedBy { it.totalAmount.toDoubleOrNull() ?: Double.MAX_VALUE }
    OfferSort.FASTEST -> offers.sortedBy { it.totalDurationMinutes }
    OfferSort.BEST -> offers
}

/**
 * Distinct slice signature: flight numbers + times of every segment, so equal
 * itineraries group regardless of fare brand.
 */
fun sliceSignature(slice: SliceDto): String =
    slice.segments.joinToString("|") {
        "${it.carrierIata}${it.flightNumber}#${it.origin}${it.destination}#${it.departureAt}#${it.arrivalAt}"
    }

/**
 * Itinerary signature of an offer: one [sliceSignature] per slice, joined.
 * Offers sharing an itinerary id (same flights, different fares) group under
 * one signature.
 */
fun itinerarySignature(offer: OfferDto): String =
    offer.slices.joinToString("~") { sliceSignature(it) }

/**
 * Round-trip partial-offer grouping, built on-device from the round-trip
 * offers:
 * - step 1: group by distinct outbound slice, cheapest first (one row per
 *   distinct outbound, priced at its cheapest fare);
 * - step 2: for the chosen outbound, distinct inbound slices, cheapest first;
 * - step 3: fares (brands) for the chosen outbound+inbound pair, cheapest first.
 *
 * Each step returns representative offers the UI renders as rows; the chosen
 * representative's signature threads the selection to the next step.
 */

/** Round-trip offers only (2+ slices). */
fun roundTripOffers(offers: List<OfferDto>): List<OfferDto> =
    offers.filter { it.slices.size >= 2 }

/** Step 1: cheapest offer per distinct outbound slice (priced at its cheapest). */
fun groupOutboundSlices(offers: List<OfferDto>): List<OfferDto> =
    roundTripOffers(offers)
        .groupBy { sliceSignature(it.slices[0]) }
        .values
        .mapNotNull { group -> group.minByOrNull { it.totalAmount.toDoubleOrNull() ?: Double.MAX_VALUE } }
        .sortedBy { it.totalAmount.toDoubleOrNull() ?: Double.MAX_VALUE }

/** Step 2: cheapest offer per distinct inbound slice, within one outbound. */
fun groupInboundSlices(offers: List<OfferDto>, outboundSignature: String): List<OfferDto> =
    roundTripOffers(offers)
        .filter { sliceSignature(it.slices[0]) == outboundSignature }
        .groupBy { sliceSignature(it.slices[1]) }
        .values
        .mapNotNull { group -> group.minByOrNull { it.totalAmount.toDoubleOrNull() ?: Double.MAX_VALUE } }
        .sortedBy { it.totalAmount.toDoubleOrNull() ?: Double.MAX_VALUE }

/** Step 3: fares (one offer per fare) for the chosen outbound+inbound pair. */
fun faresForItineraryPair(
    offers: List<OfferDto>,
    outboundSignature: String,
    inboundSignature: String,
): List<OfferDto> =
    roundTripOffers(offers)
        .filter {
            sliceSignature(it.slices[0]) == outboundSignature &&
                sliceSignature(it.slices[1]) == inboundSignature
        }
        .sortedBy { it.totalAmount.toDoubleOrNull() ?: Double.MAX_VALUE }

