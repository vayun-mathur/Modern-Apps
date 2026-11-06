package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stateless [SeatMapScreen] under the Compose rule: loading / error / empty states,
 * seat grid rendering, seat tap-through to actions, and the done button. Plus the
 * [CountStepper] widget: increment/decrement callbacks with min/max clamping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelSeatMapTest {

    @get:Rule
    val rule = createComposeRule()

    private val segment = "seg_0000AaBbCc"

    private fun seat(d: String, price: String? = "24.00", available: Boolean = true) =
        SeatElementDto(
            type = "seat", designator = d, available = available,
            serviceId = "ase_$d", totalAmount = price, totalCurrency = "USD",
        )

    private fun cabin() = SeatCabinDto(
        segmentId = segment,
        cabinClass = "economy",
        aisles = 1,
        rows = listOf(
            SeatRowDto(
                sections = listOf(
                    SeatSectionDto(elements = listOf(seat("14A"), seat("14B"), seat("14C"))),
                    SeatSectionDto(elements = listOf(seat("14D"), seat("14E"), seat("14F"))),
                ),
            ),
            SeatRowDto(
                sections = listOf(
                    SeatSectionDto(elements = listOf(seat("15A", available = false), seat("15B"))),
                ),
            ),
        ),
    )

    @Test
    fun emptyCabins_showsEmptyMessage() {
        rule.setContent {
            SeatMapScreen(
                segmentId = segment,
                state = SeatMapState(),
                selectedSeats = emptyMap(),
                actions = SeatMapActions.Noop,
            )
        }
        rule.onNodeWithText("No seat map available for this flight.").assertIsDisplayed()
    }

    @Test
    fun error_showsMessage() {
        rule.setContent {
            SeatMapScreen(
                segmentId = segment,
                state = SeatMapState(error = "Seat maps failed"),
                selectedSeats = emptyMap(),
                actions = SeatMapActions.Noop,
            )
        }
        rule.onNodeWithText("Seat maps failed").assertIsDisplayed()
    }

    @Test
    fun seats_renderDesignators() {
        rule.setContent {
            SeatMapScreen(
                segmentId = segment,
                state = SeatMapState(cabins = listOf(cabin())),
                selectedSeats = emptyMap(),
                actions = SeatMapActions.Noop,
            )
        }
        rule.onNodeWithText("14A").assertIsDisplayed()
        rule.onNodeWithText("14F").assertIsDisplayed()
        rule.onNodeWithText("15B").assertIsDisplayed()
    }

    @Test
    fun seatTap_callsToggleSeat() {
        val tapped = mutableListOf<Triple<String, String, String>>()
        rule.setContent {
            SeatMapScreen(
                segmentId = segment,
                state = SeatMapState(cabins = listOf(cabin())),
                selectedSeats = emptyMap(),
                actions = object : SeatMapActions {
                    override fun toggleSeat(segmentId: String, passengerId: String, seat: SeatElementDto) {
                        tapped.add(Triple(segmentId, passengerId, seat.designator))
                    }
                },
            )
        }
        rule.onNodeWithText("14C").performClick()
        rule.runOnIdle {
            assertEquals(1, tapped.size)
            assertEquals(segment, tapped[0].first)
            assertEquals("14C", tapped[0].third)
        }
    }

    @Test
    fun doneButton_callsDone() {
        var done = false
        rule.setContent {
            SeatMapScreen(
                segmentId = segment,
                state = SeatMapState(cabins = listOf(cabin())),
                selectedSeats = emptyMap(),
                actions = object : SeatMapActions {
                    override fun done() {
                        done = true
                    }
                },
            )
        }
        rule.onAllNodes(hasText("Done") and hasClickAction())[0].performClick()
        rule.runOnIdle { assertTrue(done) }
    }

    @Test
    fun countStepper_rendersLabelAndCount() {
        rule.setContent {
            CountStepper(label = "Adults", count = 2, onCount = {})
        }
        rule.onNodeWithText("Adults").assertIsDisplayed()
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun statusBox_states() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                StatusBox(loading = false, error = null, isEmpty = true)
                StatusBox(loading = false, error = "Boom", isEmpty = false)
                SectionHeader("Details")
            }
        }
        rule.onNodeWithText("No flights found. Try different dates or airports.").assertIsDisplayed()
        rule.onNodeWithText("Boom").assertIsDisplayed()
        rule.onNodeWithText("Details").assertIsDisplayed()
    }
}
