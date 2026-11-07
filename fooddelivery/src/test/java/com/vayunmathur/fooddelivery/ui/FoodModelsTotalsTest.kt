package com.vayunmathur.fooddelivery.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.fooddelivery.data.Brand
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.DriverLocation
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.data.OrderAddress
import com.vayunmathur.fooddelivery.data.OrderRewards
import com.vayunmathur.fooddelivery.data.OrderStage
import com.vayunmathur.fooddelivery.data.SelectedModifier
import com.vayunmathur.fooddelivery.data.parseIsoMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Order/merchant/cart derived values, ISO parsing, checkout totals UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodModelsTotalsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun merchantDerivedFieldsAndBrandFallback() {
        val m = Merchant(
            name = "Taco Place", addressStreet = "1 Main", addressCity = "Springfield",
            addressState = "IL", averageRating = null, totalRatings = null,
            freeDeliveryThreshold = 2500, imageUrl = "", logoUrl = "",
            brand = Brand(logoUrl = "brand-logo", imageUrl = "brand-img"),
        )
        assertEquals("1 Main, Springfield, IL", m.address)
        assertEquals("brand-img", m.displayImage)
        assertEquals("brand-logo", m.displayLogo)
        assertEquals(25.0, m.freeDeliveryThresholdDollars)
        assertEquals(0.0, m.displayRating)
        assertEquals(0, m.displayTotalRatings)
        assertEquals("", Merchant().address)
    }

    @Test
    fun orderTotalsDeliveryAndDoneFlags() {
        val o = Order(
            foodTotal = 2000, deliveryFee = 300, fees = 100, taxes = 200, tips = 400,
            channel = "ORDER_CHANNEL_STOREFRONT_DELIVERY", deliveredAt = "2024-01-01T00:00:00Z",
        )
        assertEquals(30.0, o.componentTotal)
        assertEquals(30.0, o.displayTotal)
        assertEquals(20.0, o.foodTotalDollars)
        assertTrue(o.isDelivery)
        assertTrue(o.isDone)
        assertEquals(OrderStage.DELIVERED, o.stage)
        assertEquals("Delivered", o.displayStatus)
        val withServerTotal = o.copy(orderTotal = 2799)
        assertEquals(27.99, withServerTotal.displayTotal)
        val pickup = Order(channel = "PICKUP", pickedupAt = "2024-01-01T00:00:00Z")
        assertFalse(pickup.isDelivery)
        assertTrue(pickup.isDone)
    }

    @Test
    fun orderStageLadderAndEta() {
        assertEquals(OrderStage.PREPARING, Order().stage)
        assertEquals(OrderStage.READY, Order(driverReachedMerchantAt = "2024-01-01T00:00:00Z").stage)
        assertEquals(OrderStage.DRIVING, Order(pickedupAt = "2024-01-01T00:00:00Z").stage)
        assertEquals(OrderStage.ARRIVING, Order(driverReachedCustomerAt = "2024-01-01T00:00:00Z").stage)
        val future = java.time.Instant.now().plusSeconds(3600).toString()
        val past = java.time.Instant.now().minusSeconds(3600).toString()
        assertEquals(OrderStage.PREPARING_SOON, Order(dueAt = future).stage)
        assertEquals(OrderStage.READY, Order(pickupReadyAt = past).stage)
        // ETA is the later of live ETA and due time.
        val eta = Order(liveDeliveryEta = past, dueAt = future)
        assertEquals(parseIsoMillis(future), eta.etaMillis)
        assertNull(Order().etaMillis)
        assertEquals(1.5, OrderRewards(rewardsAvailable = 150).rewardsAvailableDollars)
    }

    @Test
    fun driverDistanceAndPosition() {
        val noDriver = Order(address = OrderAddress(latitude = 0.0, longitude = 0.0))
        assertNull(noDriver.driverPosition)
        assertNull(noDriver.driverDistanceMiles)
        val near = Order(
            address = OrderAddress(latitude = 0.0, longitude = 0.0),
            driverLocations = listOf(
                DriverLocation(latitude = null, longitude = null),
                DriverLocation(latitude = 0.0, longitude = 0.0),
            ),
        )
        assertEquals(0.0, near.driverPosition?.latitude)
        assertEquals(0.0, near.driverDistanceMiles!!, 0.001)
        assertEquals(OrderStage.ARRIVING, near.stage)
    }

    @Test
    fun cartLineTotalCountsModifiersPerUnit() {
        val item = MenuItem(id = 1, name = "Burger", price = 1000)
        val cart = CartItem(
            menuItem = item, quantity = 2,
            selectedModifiers = listOf(SelectedModifier(name = "Cheese", price = 100, quantity = 2)),
        )
        // (1000 + 100*2) * 2 / 100 = 24.0
        assertEquals(24.0, cart.totalPrice)
        assertEquals(10.0, CartItem(menuItem = item).totalPrice)
    }

    @Test
    fun isoParsingValidInvalidAndFraction() {
        assertEquals(0L, parseIsoMillis("1970-01-01T00:00:00Z"))
        assertEquals(1500L, parseIsoMillis("1970-01-01T00:00:01.500Z"))
        assertNull(parseIsoMillis(null))
        assertNull(parseIsoMillis(""))
        assertNull(parseIsoMillis("not-a-date"))
        assertNull(parseIsoMillis("2024-13-01T00:00:00Z"))
    }

    @Test
    fun checkoutTotalsRendersRowsAndTotal() {
        composeRule.setContent {
            CheckoutTotals(
                subtotal = 20.0, fetchingPrices = false, taxesDollars = 2.0,
                deliveryFeeDollars = 3.0, feesDollars = 1.0, tipsDollars = 4.0,
                rewardsApplied = 0.0, payTotal = 30.0, displayTotal = 30.0,
            )
        }
        composeRule.onNodeWithText("$20.00").assertIsDisplayed()
        composeRule.onNodeWithText("$30.00").assertIsDisplayed()
    }

    @Test
    fun priceRowFormatsAmount() {
        composeRule.setContent { PriceRow("Taxes", 2.5) }
        composeRule.onNodeWithText("Taxes").assertIsDisplayed()
        composeRule.onNodeWithText("$2.50").assertIsDisplayed()
    }
}
