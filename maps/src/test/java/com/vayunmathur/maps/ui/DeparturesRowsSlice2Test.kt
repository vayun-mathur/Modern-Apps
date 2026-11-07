package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.util.DeparturesState
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Departure rows with the remaining field combinations: a blank headsign,
 * platform and route colour present, a loaded board whose trains all fall
 * outside the display window, and a loading board with no stop yet.
 * Slice-2 coverage for maps (basename D, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class DeparturesRowsSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val stop = TransitStop(id = "s1", name = "Ferry Terminal", lat = 37.8, lon = -122.4)

    private fun departure(
        line: String = "N",
        headsign: String = "Ocean Beach",
        inMinutes: Long = 5,
        platform: String? = null,
        routeColor: String? = null,
        cancelled: Boolean = false,
    ): Departure {
        val now = System.currentTimeMillis()
        return Departure(
            line = line,
            headsign = headsign,
            scheduledMillis = now + inMinutes * 60_000L,
            realtimeMillis = now + inMinutes * 60_000L,
            delayMinutes = 0,
            realTime = true,
            platform = platform,
            mode = "tram",
            routeColor = routeColor,
            cancelled = cancelled,
            tripVehicleId = null,
        )
    }

    @Test fun `a blank headsign shows the dash placeholder`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(departure(headsign = ""))),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("—").assertIsDisplayed()
    }

    @Test fun `platform and colour and cancellation all render on one row`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(
                    stop,
                    listOf(departure(platform = "2", routeColor = "#FF0000", cancelled = true)),
                ),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("Ocean Beach").assertIsDisplayed()
        rule.onNodeWithText("Platform 2").assertIsDisplayed()
    }

    @Test fun `trains outside the window leave an empty list under the header`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loaded(stop, listOf(departure(inMinutes = 30 * 60))),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("Ferry Terminal").assertIsDisplayed()
        rule.onNodeWithText("Ocean Beach").assertDoesNotExist()
    }

    @Test fun `a loading board shows its fetching message`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Loading(stop),
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onNodeWithText("Ferry Terminal").assertIsDisplayed()
    }

    @Test fun `an idle board shows only the generic header`() {
        rule.setContent {
            DeparturesSheet(
                state = DeparturesState.Idle,
                onDismiss = {},
                onRefresh = {},
            )
        }
        rule.onAllNodesWithText("Departures").assertCountEquals(2)
    }
}
