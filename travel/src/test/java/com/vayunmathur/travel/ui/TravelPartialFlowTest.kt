package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Partial-flow [OutboundFlightCard] plus the internal [LegLines] / [RoundTripPrice]
 * rows: collapsed header with return-option count, expand toggle, return pick,
 * and the leg/price labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelPartialFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private fun slice(origin: String = "SFO", dest: String = "JFK") = SliceDto(
        id = "sli_1", origin = origin, destination = dest,
        departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
        durationMinutes = 335, stops = 0,
        segments = listOf(
            SegmentDto(
                carrier = "Alaska Airlines", carrierIata = "AS", flightNumber = "AS 6",
                origin = origin, destination = dest,
                departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
            ),
        ),
    )

    private fun offer(id: String, amount: String = "412.30") = OfferDto(
        offerId = id, itineraryId = "itin_$id", totalAmount = amount, currency = "USD",
        owner = "Alaska Airlines", ownerIata = "AS",
        slices = listOf(slice(), slice("JFK", "SFO")),
    )

    @Test
    fun collapsed_showsCountAndFromPrice() {
        rule.setContent {
            OutboundFlightCard(
                outbound = offer("o1"),
                returns = listOf(offer("r1"), offer("r2")),
                expanded = false,
                onToggle = {},
                onPickReturn = {},
            )
        }
        // "2 return flights" plural + from-price.
        rule.onNodeWithText("2 return flights", substring = true).assertIsDisplayed()
        rule.onNodeWithText("$412.30").assertIsDisplayed()
        rule.onNodeWithText("Alaska Airlines").assertIsDisplayed()
    }

    @Test
    fun toggle_expandsReturns() {
        var expanded = false
        var toggles = 0
        rule.setContent {
            OutboundFlightCard(
                outbound = offer("o1"),
                returns = listOf(offer("r1", "430.00")),
                expanded = expanded,
                onToggle = { toggles++; expanded = true },
                onPickReturn = {},
            )
        }
        rule.onNodeWithText("Alaska Airlines").performClick()
        rule.runOnIdle { assertEquals(1, toggles) }
    }

    @Test
    fun expandedReturn_pickFires() {
        val picked = mutableListOf<String>()
        rule.setContent {
            OutboundFlightCard(
                outbound = offer("o1"),
                returns = listOf(offer("r1", "430.00")),
                expanded = true,
                onToggle = {},
                onPickReturn = { picked.add(it.offerId) },
            )
        }
        rule.onNodeWithText("$430").performClick()
        rule.runOnIdle { assertEquals(listOf("r1"), picked) }
    }

    @Test
    fun legLines_rendersRouteStopsDuration() {
        rule.setContent { LegLines(slice(), "Alaska Airlines") }
        rule.onNodeWithText("Alaska Airlines").assertIsDisplayed()
        rule.onNodeWithText("Nonstop · 5h 35m").assertIsDisplayed()
    }

    @Test
    fun roundTripPrice_fromAndTotal() {
        rule.setContent { RoundTripPrice(offer("o1"), from = true) }
        rule.onNodeWithText("$412.30").assertIsDisplayed()
        var shown = false
        rule.onNodeWithText("Round trip", substring = true).assertIsDisplayed()
        shown = true
        assertTrue(shown)
    }
}
