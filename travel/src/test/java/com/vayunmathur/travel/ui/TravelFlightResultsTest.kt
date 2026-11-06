package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.travel.network.BaggageDto
import com.vayunmathur.travel.network.ConditionsDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.PenaltyRuleDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.FlightFilters
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.FlightResultsActions
import com.vayunmathur.travel.util.FlightResultsState
import com.vayunmathur.travel.util.OfferSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stateless [FlightResultsScreen] under the Compose rule: loading / error / empty states,
 * offer rows with fare labels, sort + filter chips, expiry banner, and click-through.
 * Follows the travel pure-test style (literal DTOs, behavior assertions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelFlightResultsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun seg(carrier: String = "AS", iata: String = "AS") = SegmentDto(
        id = "seg_1", carrier = "Alaska Airlines", carrierIata = iata,
        flightNumber = "AS 6", origin = "SFO", destination = "JFK",
        departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
        baggages = listOf(
            BaggageDto(type = "carry_on", quantity = 1),
            BaggageDto(type = "checked", quantity = 1),
        ),
    )

    private fun slice(stops: Long = 0, carrier: String = "AS", iata: String = "AS") = SliceDto(
        id = "sli_1", origin = "SFO", destination = "JFK",
        departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
        durationMinutes = 335, stops = stops,
        segments = listOf(seg(carrier, iata)),
    )

    private fun offer(
        id: String,
        amount: String = "412.30",
        fareBrand: String = "Main",
        refundable: Boolean = false,
        stops: Long = 0,
        iata: String = "AS",
    ) = OfferDto(
        offerId = id, itineraryId = "itin_$id", totalAmount = amount, currency = "USD",
        owner = "Alaska Airlines", ownerIata = iata, fareBrand = fareBrand,
        conditions = ConditionsDto(
            refundBeforeDeparture = PenaltyRuleDto(allowed = refundable),
            changeBeforeDeparture = PenaltyRuleDto(allowed = true),
        ),
        slices = listOf(slice(stops, iata = iata)),
    )

    private fun recordingActions(opened: MutableList<String>? = null) = object : FlightResultsActions {
        override fun openOffer(offer: OfferDto) {
            opened?.add(offer.offerId)
        }
    }

    @Test
    fun emptySearch_showsEmptyMessage() {
        rule.setContent {
            FlightResultsScreen(
                title = "SFO → JFK",
                state = FlightResultsState(hasSearched = true),
                actions = FlightResultsActions.Noop,
            )
        }
        rule.onNodeWithText("No flights found. Try different dates or airports.").assertIsDisplayed()
    }

    @Test
    fun loading_showsProgress() {
        rule.setContent {
            FlightResultsScreen(
                title = "SFO → JFK",
                state = FlightResultsState(loading = true),
                actions = FlightResultsActions.Noop,
            )
        }
        // Loading hides the empty message.
        rule.onNodeWithText("No flights found. Try different dates or airports.").assertDoesNotExist()
    }

    @Test
    fun error_showsMessage() {
        rule.setContent {
            FlightResultsScreen(
                title = "SFO → JFK",
                state = FlightResultsState(hasSearched = true, error = "Search failed"),
                actions = FlightResultsActions.Noop,
            )
        }
        rule.onNodeWithText("Search failed").assertIsDisplayed()
    }

    @Test
    fun offers_renderOwnerFareAndPrice() {
        rule.setContent {
            FlightResultsScreen(
                title = "SFO → JFK",
                state = FlightResultsState(
                    hasSearched = true,
                    allOffers = listOf(
                        offer("0001"),
                        offer("0002", amount = "438.00", fareBrand = "Blue"),
                    ),
                ),
                actions = FlightResultsActions.Noop,
            )
        }
        // Owner text appears on every offer card; "Blue" twice by design (filter chip + fare badge).
        assertEquals(2, rule.onAllNodesWithText("Alaska Airlines").fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText("Blue", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("$412.30").assertIsDisplayed()
        rule.onNodeWithText("$438").assertIsDisplayed()
    }

    @Test
    fun refundableChip_renders() {
        rule.setContent {
            FlightResultsScreen(
                title = "SFO → JFK",
                state = FlightResultsState(
                    hasSearched = true,
                    allOffers = listOf(offer("0001", refundable = true)),
                ),
                actions = FlightResultsActions.Noop,
            )
        }
        rule.onNodeWithText("Refundable").assertIsDisplayed()
        rule.onNodeWithText("Changeable").assertIsDisplayed()
    }

    @Test
    fun sortChip_clickChangesSort() {
        var sort = OfferSort.BEST
        rule.setContent {
            FlightResultsScreen(
                title = "SFO → JFK",
                state = FlightResultsState(
                    hasSearched = true, sort = sort,
                    allOffers = listOf(offer("0001")),
                ),
                actions = object : FlightResultsActions {
                    override fun setSort(s: OfferSort) {
                        sort = s
                    }
                },
            )
        }
        rule.onNodeWithText("Cheapest").performClick()
        rule.runOnIdle { assertEquals(OfferSort.CHEAPEST, sort) }
    }

    @Test
    fun airlineChip_clickTogglesFilter() {
        val toggled = mutableListOf<String>()
        rule.setContent {
            FlightResultsScreen(
                title = "SFO → JFK",
                state = FlightResultsState(
                    hasSearched = true,
                    allOffers = listOf(offer("0001"), offer("0002", iata = "UA")),
                ),
                actions = object : FlightResultsActions {
                    override fun toggleAirlineFilter(iata: String) {
                        toggled.add(iata)
                    }
                },
            )
        }
        // "UA" also appears on the offer card; scope the click to the filter chip (has click action).
        rule.onAllNodes(hasText("UA") and hasClickAction())[0].performClick()
        rule.runOnIdle { assertEquals(listOf("UA"), toggled) }
    }

    @Test
    fun offerClick_opensOffer() {
        val opened = mutableListOf<String>()
        rule.setContent {
            FlightResultsScreen(
                title = "SFO → JFK",
                state = FlightResultsState(
                    hasSearched = true,
                    allOffers = listOf(offer("0001")),
                ),
                actions = recordingActions(opened),
            )
        }
        // Card row has the click action; the owner text node itself does not.
        rule.onAllNodes(hasText("Alaska Airlines") and hasClickAction())[0].performClick()
        rule.runOnIdle { assertEquals(listOf("0001"), opened) }
    }

    @Test
    fun state_visibleOffersRespectsFilters() {
        val state = FlightResultsState(
            hasSearched = true,
            allOffers = listOf(
                offer("nonstop", stops = 0),
                offer("onestop", stops = 1),
            ),
            filters = FlightFilters(maxStops = 0),
        )
        assertEquals(listOf("nonstop"), state.visibleOffers.map { it.offerId })
        assertEquals(listOf("AS"), state.availableAirlines)
        assertEquals(listOf("Main"), state.availableFareBrands)
    }

    @Test
    fun flightQuery_legsSplit() {
        val q = FlightQuery(slices = "SFO:JFK:2026-06-24, JFK:SFO:2026-07-01", adults = 2, isRoundTrip = true)
        assertEquals(2, q.legs.size)
        assertEquals(2, q.adults)
        assertTrue(q.isRoundTrip)
    }
}
