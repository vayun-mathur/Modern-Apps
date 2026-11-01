package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.travel.network.BaggageDto
import com.vayunmathur.travel.network.ConditionsDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.PenaltyRuleDto
import com.vayunmathur.travel.network.SeatCabinDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.SeatRowDto
import com.vayunmathur.travel.network.SeatSectionDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.util.FlightResultsActions
import com.vayunmathur.travel.util.FlightResultsState
import com.vayunmathur.travel.util.OrderDetailActions
import com.vayunmathur.travel.util.OrderDetailUiState
import com.vayunmathur.travel.util.SeatMapActions
import com.vayunmathur.travel.util.SeatMapState
import com.vayunmathur.travel.util.StayResultsActions
import com.vayunmathur.travel.util.StaySearchState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window — comfortably above the 840dp expanded-width threshold. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/** The segment the seat map belongs to; seat selections are keyed by `"segmentId|designator"`. */
private const val SEGMENT = "seg_0000AaBbCc"

/**
 * Store listing images for `:travel`. See `common-conventions-preview-metadata`.
 *
 * The four screens walk the booking: pick a fare, pick a seat, see the booked trip,
 * and the hotel side of the app.
 *
 * Everything is a literal DTO. That matters more here than in the other apps, because every
 * one of these screens is normally filled by a live flight search:
 *
 *  - No logo or photo URLs. Layoutlib has no network, and both `AirlineLogo` and the stay
 *    card fall back cleanly to a badge / no image when the URL is blank.
 *  - No `expiresAt` on the offers. The price-hold banner counts down from `Clock.System.now()`,
 *    which would make the rendered PNG depend on when it was rendered.
 *  - Dates are fixed and in the future, so the images stay stable across re-renders.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    // --- Sample data builders ------------------------------------------------

    /** One nonstop-or-not SFO→JFK offer, carrying only what the offer card draws. */
    private fun offer(
        id: String,
        airline: String,
        iata: String,
        flightNumber: String,
        departureAt: String,
        arrivalAt: String,
        durationMinutes: Long,
        stops: Long,
        fareBrand: String,
        amount: String,
        refundable: Boolean = false,
    ) = OfferDto(
        offerId = id,
        totalAmount = amount,
        currency = "USD",
        owner = airline,
        ownerIata = iata,
        fareBrand = fareBrand,
        conditions = ConditionsDto(
            refundBeforeDeparture = PenaltyRuleDto(allowed = refundable),
            changeBeforeDeparture = PenaltyRuleDto(allowed = true),
        ),
        slices = listOf(
            SliceDto(
                id = "sli_$id",
                origin = "SFO",
                destination = "JFK",
                departureAt = departureAt,
                arrivalAt = arrivalAt,
                durationMinutes = durationMinutes,
                stops = stops,
                segments = listOf(
                    SegmentDto(
                        id = "seg_$id",
                        carrier = airline,
                        carrierIata = iata,
                        flightNumber = flightNumber,
                        origin = "SFO",
                        destination = "JFK",
                        departureAt = departureAt,
                        arrivalAt = arrivalAt,
                        baggages = listOf(
                            BaggageDto(type = "carry_on", quantity = 1),
                            BaggageDto(type = "checked", quantity = 1),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** One seat. A null [price] renders the chip without a price line, i.e. included. */
    private fun seat(designator: String, price: String?, available: Boolean = true) = SeatElementDto(
        type = "seat",
        designator = designator,
        available = available,
        serviceId = "ase_$designator",
        totalAmount = price,
        totalCurrency = "USD",
    )

    /** A 3-3 row: seats ABC, the aisle, then seats DEF. */
    private fun seatRow(number: Int, taken: Set<String> = emptySet(), price: String? = "24.00") =
        SeatRowDto(
            sections = listOf("ABC", "DEF").map { letters ->
                SeatSectionDto(
                    elements = letters.map { letter ->
                        val designator = "$number$letter"
                        seat(designator, price, available = designator !in taken)
                    },
                )
            },
        )

    /** A row with no seats in it, drawn as a band spanning each section. */
    private fun exitRow() = SeatRowDto(
        sections = List(2) { SeatSectionDto(elements = listOf(SeatElementDto(type = "exit_row"))) },
    )

    // --- Previews ------------------------------------------------------------

    @PreviewTest
    @Preview(name = "1-flights", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Flights() {
        DynamicTheme(darkTheme = true) {
            FlightResultsScreen(
                title = "SFO → JFK",
                state = FlightResultsState(
                    hasSearched = true,
                    offerRequestId = "orq_0000AaBbCc",
                    allOffers = listOf(
                        offer(
                            id = "0001", airline = "Alaska Airlines", iata = "AS", flightNumber = "AS 6",
                            departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
                            durationMinutes = 335, stops = 0, fareBrand = "Main", amount = "412.30",
                        ),
                        offer(
                            id = "0002", airline = "JetBlue", iata = "B6", flightNumber = "B6 916",
                            departureAt = "2026-06-24T08:40:00", arrivalAt = "2026-06-24T17:19:00",
                            durationMinutes = 339, stops = 0, fareBrand = "Blue", amount = "438.00",
                        ),
                        offer(
                            id = "0003", airline = "Delta", iata = "DL", flightNumber = "DL 410",
                            departureAt = "2026-06-24T11:05:00", arrivalAt = "2026-06-24T19:35:00",
                            durationMinutes = 330, stops = 0, fareBrand = "Main", amount = "466.80",
                            refundable = true,
                        ),
                        offer(
                            id = "0004", airline = "United", iata = "UA", flightNumber = "UA 2118",
                            departureAt = "2026-06-24T06:00:00", arrivalAt = "2026-06-24T17:24:00",
                            durationMinutes = 444, stops = 1, fareBrand = "Basic", amount = "329.20",
                        ),
                    ),
                ),
                actions = FlightResultsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-seats", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Seats() {
        DynamicTheme(darkTheme = true) {
            SeatMapScreen(
                segmentId = SEGMENT,
                state = SeatMapState(
                    cabins = listOf(
                        SeatCabinDto(
                            segmentId = SEGMENT,
                            cabinClass = "economy",
                            aisles = 1,
                            rows = listOf(
                                seatRow(10, taken = setOf("10B", "10C")),
                                seatRow(11, taken = setOf("11E")),
                                seatRow(12, taken = setOf("12A", "12F")),
                                exitRow(),
                                seatRow(14, price = "38.00"),
                                seatRow(15, taken = setOf("15C", "15D")),
                                seatRow(16),
                                seatRow(17, taken = setOf("17A"), price = null),
                                seatRow(18, price = null),
                                seatRow(19, taken = setOf("19B", "19E", "19F"), price = null),
                            ),
                        ),
                    ),
                ),
                selectedSeats = mapOf(
                    "$SEGMENT|pax_1" to seat("14C", "38.00"),
                    "$SEGMENT|pax_2" to seat("14D", "38.00"),
                ),
                actions = SeatMapActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-order", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Order() {
        DynamicTheme(darkTheme = true) {
            OrderDetailScreen(
                state = OrderDetailUiState(
                    order = OrderDetailDto(
                        orderId = "ord_0000AaBbCc",
                        bookingReference = "K7QZ4M",
                        totalAmount = "824.60",
                        currency = "USD",
                        status = "confirmed",
                        paymentStatus = "paid",
                        passengerNames = listOf("Jane Ashworth", "Tom Ashworth"),
                        slices = listOf(
                            SliceDto(
                                id = "sli_out",
                                origin = "SFO",
                                destination = "JFK",
                                departureAt = "2026-06-24T07:15:00",
                                arrivalAt = "2026-06-24T15:50:00",
                                durationMinutes = 335,
                                segments = listOf(
                                    SegmentDto(
                                        id = "seg_out",
                                        carrier = "Alaska Airlines",
                                        carrierIata = "AS",
                                        flightNumber = "AS 6",
                                        origin = "SFO",
                                        destination = "JFK",
                                        departureAt = "2026-06-24T07:15:00",
                                        arrivalAt = "2026-06-24T15:50:00",
                                    ),
                                ),
                            ),
                            SliceDto(
                                id = "sli_ret",
                                origin = "JFK",
                                destination = "SFO",
                                departureAt = "2026-07-02T17:30:00",
                                arrivalAt = "2026-07-02T21:05:00",
                                durationMinutes = 395,
                                segments = listOf(
                                    SegmentDto(
                                        id = "seg_ret",
                                        carrier = "Alaska Airlines",
                                        carrierIata = "AS",
                                        flightNumber = "AS 15",
                                        origin = "JFK",
                                        destination = "SFO",
                                        departureAt = "2026-07-02T17:30:00",
                                        arrivalAt = "2026-07-02T21:05:00",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                actions = OrderDetailActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-stays", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Stays() {
        DynamicTheme(darkTheme = true) {
            StayResultsScreen(
                place = "Paris",
                state = StaySearchState(
                    hasSearched = true,
                    results = listOf(
                        StaySearchResultDto(
                            id = "acc_0001",
                            name = "Hôtel Saint-Germain",
                            rating = 4,
                            reviewScore = 8.7,
                            address = "12 Rue de Vaugirard, 75006 Paris",
                            amenities = listOf("Wi-Fi", "Breakfast", "Air conditioning", "24h reception"),
                            cheapestAmount = "186.00",
                            cheapestCurrency = "EUR",
                        ),
                        StaySearchResultDto(
                            id = "acc_0002",
                            name = "Grand Hôtel Opéra",
                            rating = 5,
                            reviewScore = 9.1,
                            address = "2 Boulevard des Capucines, 75009 Paris",
                            amenities = listOf("Wi-Fi", "Spa", "Pool", "Fitness centre"),
                            cheapestAmount = "324.00",
                            cheapestCurrency = "EUR",
                        ),
                        StaySearchResultDto(
                            id = "acc_0003",
                            name = "Le Marais Boutique",
                            rating = 3,
                            reviewScore = 8.2,
                            address = "9 Rue de Bretagne, 75003 Paris",
                            amenities = listOf("Wi-Fi", "Bar", "Pet friendly"),
                            cheapestAmount = "142.50",
                            cheapestCurrency = "EUR",
                        ),
                        StaySearchResultDto(
                            id = "acc_0004",
                            name = "Canal Saint-Martin Loft",
                            rating = 3,
                            reviewScore = 7.9,
                            address = "48 Quai de Jemmapes, 75010 Paris",
                            amenities = listOf("Wi-Fi", "Kitchen", "Laundry"),
                            cheapestAmount = "98.00",
                            cheapestCurrency = "EUR",
                        ),
                    ),
                ),
                actions = StayResultsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5Expanded() {
        DynamicTheme(darkTheme = true) {
            // Expanded windows keep the flight results visible while the selected
            // booking's order detail opens beside them instead of covering them.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    FlightResultsScreen(
                        title = "SFO → JFK",
                        state = FlightResultsState(
                            hasSearched = true,
                            offerRequestId = "orq_0000AaBbCc",
                            allOffers = listOf(
                                offer(
                                    id = "0001", airline = "Alaska Airlines", iata = "AS", flightNumber = "AS 6",
                                    departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
                                    durationMinutes = 335, stops = 0, fareBrand = "Main", amount = "412.30",
                                ),
                                offer(
                                    id = "0002", airline = "JetBlue", iata = "B6", flightNumber = "B6 916",
                                    departureAt = "2026-06-24T08:40:00", arrivalAt = "2026-06-24T17:19:00",
                                    durationMinutes = 339, stops = 0, fareBrand = "Blue", amount = "438.00",
                                ),
                                offer(
                                    id = "0003", airline = "Delta", iata = "DL", flightNumber = "DL 410",
                                    departureAt = "2026-06-24T11:05:00", arrivalAt = "2026-06-24T19:35:00",
                                    durationMinutes = 330, stops = 0, fareBrand = "Main", amount = "466.80",
                                    refundable = true,
                                ),
                                offer(
                                    id = "0004", airline = "United", iata = "UA", flightNumber = "UA 2118",
                                    departureAt = "2026-06-24T06:00:00", arrivalAt = "2026-06-24T17:24:00",
                                    durationMinutes = 444, stops = 1, fareBrand = "Basic", amount = "329.20",
                                ),
                            ),
                        ),
                        actions = FlightResultsActions.Noop,
                    )
                }
                Box(Modifier.weight(0.6f).fillMaxHeight()) {
                    OrderDetailScreen(
                        state = OrderDetailUiState(
                            order = OrderDetailDto(
                                orderId = "ord_0000AaBbCc",
                                bookingReference = "K7QZ4M",
                                totalAmount = "824.60",
                                currency = "USD",
                                status = "confirmed",
                                paymentStatus = "paid",
                                passengerNames = listOf("Jane Ashworth", "Tom Ashworth"),
                                slices = listOf(
                                    SliceDto(
                                        id = "sli_out",
                                        origin = "SFO",
                                        destination = "JFK",
                                        departureAt = "2026-06-24T07:15:00",
                                        arrivalAt = "2026-06-24T15:50:00",
                                        durationMinutes = 335,
                                        segments = listOf(
                                            SegmentDto(
                                                id = "seg_out",
                                                carrier = "Alaska Airlines",
                                                carrierIata = "AS",
                                                flightNumber = "AS 6",
                                                origin = "SFO",
                                                destination = "JFK",
                                                departureAt = "2026-06-24T07:15:00",
                                                arrivalAt = "2026-06-24T15:50:00",
                                            ),
                                        ),
                                    ),
                                    SliceDto(
                                        id = "sli_ret",
                                        origin = "JFK",
                                        destination = "SFO",
                                        departureAt = "2026-07-02T17:30:00",
                                        arrivalAt = "2026-07-02T21:05:00",
                                        durationMinutes = 395,
                                        segments = listOf(
                                            SegmentDto(
                                                id = "seg_ret",
                                                carrier = "Alaska Airlines",
                                                carrierIata = "AS",
                                                flightNumber = "AS 15",
                                                origin = "JFK",
                                                destination = "SFO",
                                                departureAt = "2026-07-02T17:30:00",
                                                arrivalAt = "2026-07-02T21:05:00",
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        actions = OrderDetailActions.Noop,
                    )
                }
            }
        }
    }
}
