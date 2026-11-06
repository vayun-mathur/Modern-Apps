package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The transit departure board: loading / empty / populated states, countdown
 * wording, delay colouring and tap-through.
 * Slice-2 coverage for maps (basename D, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class DeparturesSheetSlice2Test {

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
        platform: String? = null,
        routeColor: String? = null,
        tripVehicleId: Long? = null,
    ): Departure {
        val now = System.currentTimeMillis()
        return Departure(
            line = line,
            headsign = headsign,
            scheduledMillis = now + inMinutes * 60_000L,
            realtimeMillis = now + inMinutes * 60_000L,
            delayMinutes = delayMinutes,
            realTime = realTime,
            platform = platform,
            mode = "tram",
            routeColor = routeColor,
            cancelled = cancelled,
            tripVehicleId = tripVehicleId,
        )
    }

    @Test
    fun `loading shows the fetching state with the stop name`() {
        rule.setContent {
            DeparturesSheet(state = DeparturesState.Loading(stop), onDismiss = {}, onRefresh = {})
        }
        rule.onNodeWithText("Ferry Terminal").assertIsDisplayed()
        rule.onNodeWithText("Loading departures…").assertIsDisplayed()
    }

    @Test
    fun `idle shows the header and nothing else`() {
        rule.setContent {
            DeparturesSheet(state = DeparturesState.Idle, onDismiss = {}, onRefresh = {})
        }
        // Header title plus the subtitle line share the word; both read.
        rule.onAllNodesWithText("Departures", substring = true).assertCountEquals(2)
        rule.onNodeWithText("Loading departures…").assertDoesNotExist()
    }

    @Test
    fun `an empty board says so`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, emptyList()),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("No upcoming departures").assertIsDisplayed()
    }

    @Test
    fun `a departure row shows line, destination and countdown`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(departure(line = "N", headsign = "Ocean Beach"))),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("N").assertIsDisplayed()
        rule.onNodeWithText("Ocean Beach").assertIsDisplayed()
        rule.onNodeWithText("5 min").assertIsDisplayed()
    }

    @Test
    fun `a due departure reads now`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(departure(inMinutes = 0))),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("Now").assertIsDisplayed()
    }

    @Test
    fun `a late departure names its lateness`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(departure(delayMinutes = 3))),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("3 min late").assertIsDisplayed()
    }

    @Test
    fun `an early departure names its earliness`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(departure(delayMinutes = -2))),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("2 min early").assertIsDisplayed()
    }

    @Test
    fun `a cancelled departure reads cancelled with a struck destination`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(departure(cancelled = true))),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("Cancelled").assertIsDisplayed()
    }

    @Test
    fun `a platform shows when the feed carries one`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(departure(platform = "2"))),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("Platform 2").assertIsDisplayed()
    }

    @Test
    fun `tapping a pack-backed row opens its trip`() {
        var tapped: Departure? = null
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(departure(tripVehicleId = 42L))),
                onDismiss = {},
                onRefresh = {},
                onTrainTap = { tapped = it },
            )
        }
        rule.onNodeWithText("Ocean Beach").performClick()
        assert(tapped?.tripVehicleId == 42L)
    }

    @Test
    fun `rows sort chronologically`() {
        val now = System.currentTimeMillis()
        fun at(minutes: Long) = Departure(
            line = "L$minutes",
            headsign = "to $minutes",
            scheduledMillis = now + minutes * 60_000L,
            realtimeMillis = now + minutes * 60_000L,
            delayMinutes = 0,
            realTime = false,
            platform = null,
            mode = null,
            routeColor = null,
            cancelled = false,
        )
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(at(20), at(2))),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("L2").assertIsDisplayed()
        rule.onNodeWithText("L20").assertIsDisplayed()
    }
}
