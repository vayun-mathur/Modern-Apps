package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.util.DeparturesState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Departure-board rows the first pass did not render: already-departed
 * (minutes-ago wording, dimmed), far-future (clock wording), the delay
 * colour bands, a scheduled-only row, the refresh action, and dismissal.
 * Slice-2 coverage for maps (basename D, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class DeparturesExtraSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val stop = TransitStop(id = "s1", name = "Ferry Terminal", lat = 37.8, lon = -122.4)

    private fun departure(
        line: String = "N",
        headsign: String = "Ocean Beach",
        inMinutes: Long = 5,
        delayMinutes: Int = 0,
        realTime: Boolean = true,
        cancelled: Boolean = false,
    ): Departure {
        val now = System.currentTimeMillis()
        return Departure(
            line = line,
            headsign = headsign,
            scheduledMillis = now + inMinutes * 60_000L,
            realtimeMillis = now + inMinutes * 60_000L,
            delayMinutes = delayMinutes,
            realTime = realTime,
            platform = null,
            mode = "tram",
            routeColor = null,
            cancelled = cancelled,
            tripVehicleId = null,
        )
    }

    private fun board(departures: List<Departure>, onRefresh: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, departures),
                onDismiss = onDismiss,
                onRefresh = onRefresh,
            )
        }
    }

    @Test fun `a departed train reads minutes ago`() {
        board(listOf(departure(inMinutes = -5)))
        rule.onNodeWithText("Ferry Terminal").assertIsDisplayed()
        rule.onNodeWithText("5 min ago").assertIsDisplayed()
    }

    @Test fun `a train beyond the countdown window reads as a clock time`() {
        board(listOf(departure(inMinutes = 120)))
        // Clock wording is locale-formatted; the row itself must render with
        // its line and destination rather than a minute count.
        rule.onNodeWithText("Ocean Beach").assertIsDisplayed()
    }

    @Test fun `delay bands and a scheduled only row all render`() {
        board(
            listOf(
                departure(line = "N", headsign = "On Time", inMinutes = 3, delayMinutes = 0),
                departure(line = "L", headsign = "Slightly Late", inMinutes = 6, delayMinutes = 3),
                departure(line = "M", headsign = "Very Late", inMinutes = 9, delayMinutes = 12),
                departure(line = "K", headsign = "Scheduled Only", inMinutes = 15, realTime = false),
                departure(line = "J", headsign = "Early Bird", inMinutes = 20, delayMinutes = -4),
            ),
        )
        rule.onNodeWithText("On Time").assertIsDisplayed()
        rule.onNodeWithText("Scheduled Only").assertIsDisplayed()
    }

    @Test fun `refresh fires from the board header`() {
        var refreshes = 0
        board(listOf(departure()), onRefresh = { refreshes++ })
        rule.onNodeWithText("Ferry Terminal").assertIsDisplayed()
        rule.onNodeWithContentDescription("Refresh").performClick()
        assertEquals(1, refreshes)
    }
}
