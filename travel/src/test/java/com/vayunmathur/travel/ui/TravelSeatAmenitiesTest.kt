package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.travel.network.SeatCabinDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.SeatRowDto
import com.vayunmathur.travel.network.SeatSectionDto
import com.vayunmathur.travel.util.SeatMapActions
import com.vayunmathur.travel.util.SeatMapState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seat map with amenity bands (lavatory/galley/exit) and two-passenger selection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelSeatAmenitiesTest {

    @get:Rule val rule = createComposeRule()

    private fun el(type: String, d: String = "", available: Boolean = true) =
        SeatElementDto(type = type, designator = d, available = available, serviceId = "s$d", totalAmount = "10.00", totalCurrency = "USD")

    private fun cabin() = SeatCabinDto(
        segmentId = "seg_1", cabinClass = "economy", aisles = 1,
        rows = listOf(
            SeatRowDto(sections = listOf(
                SeatSectionDto(elements = listOf(el("seat", "1A"), el("seat", "1B"))),
                SeatSectionDto(elements = listOf(el("lavatory"), el("galley"))),
            )),
            SeatRowDto(sections = listOf(
                SeatSectionDto(elements = listOf(el("exit_row"), el("seat", "2A", available = false), el("empty"))),
            )),
        ),
    )

    @Test fun amenitiesAndSeats_render() {
        rule.setContent {
            SeatMapScreen(segmentId = "seg_1", state = SeatMapState(cabins = listOf(cabin())), selectedSeats = emptyMap(), actions = SeatMapActions.Noop)
        }
        rule.onNodeWithText("1A").assertIsDisplayed()
        rule.onNodeWithText("2A").assertIsDisplayed()
    }

    @Test fun seatTap_forSecondPassenger() {
        val tapped = mutableListOf<String>()
        rule.setContent {
            SeatMapScreen(
                segmentId = "seg_1", state = SeatMapState(cabins = listOf(cabin())),
                selectedSeats = emptyMap(),
                actions = object : SeatMapActions {
                    override fun toggleSeat(segmentId: String, passengerId: String, seat: SeatElementDto) { tapped.add(seat.designator) }
                },
                passengers = listOf(
                    SeatPassenger("pax_1", "Jane Doe"),
                    SeatPassenger("pax_2", "John Doe"),
                ),
            )
        }
        rule.onNodeWithText("1B").performClick()
        rule.runOnIdle { assertEquals(listOf("1B"), tapped) }
    }

    @Test fun loadingAndDone_states() {
        rule.setContent {
            SeatMapScreen(segmentId = "seg_1", state = SeatMapState(loading = true), selectedSeats = emptyMap(), actions = SeatMapActions.Noop)
        }
        rule.onNodeWithText("Choose seats", substring = true).assertExists()
    }
}
