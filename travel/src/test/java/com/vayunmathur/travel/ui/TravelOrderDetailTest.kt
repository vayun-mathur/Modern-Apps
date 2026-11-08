package com.vayunmathur.travel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.SegmentDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.CheckoutState
import com.vayunmathur.travel.util.OrderDetailActions
import com.vayunmathur.travel.util.OrderDetailUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stateless [OrderDetailScreen] under the Compose rule: null-order states, booked-trip
 * card with fare + itinerary, cancel/change buttons gated on flags, and the
 * pending-checkout payment button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelOrderDetailTest {

    @get:Rule
    val rule = createComposeRule()

    private fun slice() = SliceDto(
        id = "sli_out", origin = "SFO", destination = "JFK",
        departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
        durationMinutes = 335,
        segments = listOf(
            SegmentDto(
                id = "seg_out", carrier = "Alaska Airlines", carrierIata = "AS",
                flightNumber = "AS 6", origin = "SFO", destination = "JFK",
                departureAt = "2026-06-24T07:15:00", arrivalAt = "2026-06-24T15:50:00",
            ),
        ),
    )

    private fun order() = OrderDetailDto(
        orderId = "ord_1", bookingReference = "K7QZ4M",
        totalAmount = "824.60", currency = "USD",
        status = "confirmed", paymentStatus = "paid",
        passengerNames = listOf("Jane Ashworth", "Tom Ashworth"),
        slices = listOf(slice()),
        canCancel = true, canChange = true,
    )

    @Test
    fun nullOrder_showsNotFound() {
        rule.setContent {
            OrderDetailScreen(state = OrderDetailUiState(), actions = OrderDetailActions.Noop)
        }
        rule.onNodeWithText("Order not found.").assertIsDisplayed()
    }

    @Test
    fun nullOrder_errorShowsMessage() {
        rule.setContent {
            OrderDetailScreen(
                state = OrderDetailUiState(error = "Load failed"),
                actions = OrderDetailActions.Noop,
            )
        }
        rule.onNodeWithText("Load failed").assertIsDisplayed()
    }

    @Test
    fun order_rendersReferenceTotalAndPassengers() {
        rule.setContent {
            OrderDetailScreen(
                state = OrderDetailUiState(order = order()),
                actions = OrderDetailActions.Noop,
            )
        }
        rule.onNodeWithText("K7QZ4M").assertIsDisplayed()
        rule.onNodeWithText("$824.60").assertIsDisplayed()
        rule.onNodeWithText("Jane Ashworth, Tom Ashworth").assertIsDisplayed()
        rule.onNodeWithText("Alaska Airlines AS 6").assertIsDisplayed()
    }

    @Test
    fun cancelAndChange_buttonsFire() {
        val calls = mutableListOf<String>()
        rule.setContent {
            OrderDetailScreen(
                state = OrderDetailUiState(order = order()),
                actions = object : OrderDetailActions {
                    override fun openChange(orderId: String) {
                        calls.add("change:$orderId")
                    }
                    override fun openCancel(orderId: String) {
                        calls.add("cancel:$orderId")
                    }
                },
            )
        }
        rule.onNodeWithText("Change flights").performScrollTo()
        rule.onNodeWithText("Change flights").assertIsDisplayed()
        rule.onNodeWithText("Change flights").performClick()
        rule.runOnIdle {
            assertTrue(calls.contains("change:ord_1"), "calls=$calls")
        }
        rule.onNodeWithText("Cancel order").performScrollTo()
        rule.onNodeWithText("Cancel order").performClick()
        rule.runOnIdle {
            assertTrue(calls.contains("cancel:ord_1"), "calls=$calls")
        }
    }

    @Test
    fun cancelledOrder_hidesCancelChange() {
        rule.setContent {
            OrderDetailScreen(
                state = OrderDetailUiState(order = order().copy(status = "cancelled")),
                actions = OrderDetailActions.Noop,
            )
        }
        assertEquals(
            0,
            rule.onAllNodesWithText("Change flights", substring = true).fetchSemanticsNodes().size,
        )
        assertEquals(
            0,
            rule.onAllNodesWithText("Cancel order", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun refreshButton_callsRefresh() {
        var refreshed = false
        rule.setContent {
            OrderDetailScreen(
                state = OrderDetailUiState(order = order().copy(canCancel = false, canChange = false)),
                actions = object : OrderDetailActions {
                    override fun refresh() {
                        refreshed = true
                    }
                },
            )
        }
        rule.onNodeWithText("Refresh status").performScrollTo()
        rule.onNodeWithText("Refresh status").performClick()
        rule.runOnIdle { assertTrue(refreshed) }
    }

    @Test
    fun pendingCheckout_showsPayButton() {
        rule.setContent {
            OrderDetailScreen(
                state = OrderDetailUiState(order = order(), checkout = CheckoutState.Idle),
                actions = OrderDetailActions.Noop,
                pendingCheckout = true,
            )
        }
        assertEquals(
            1,
            rule.onAllNodesWithText("Complete payment", substring = true).fetchSemanticsNodes().size,
        )
    }
}
