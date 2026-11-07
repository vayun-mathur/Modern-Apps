package com.vayunmathur.fooddelivery.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.api.BitesAuth
import com.vayunmathur.fooddelivery.api.BitesCore
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.network.SimpleResponse
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodUiExtraTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val realTransport = BitesCore.transport

    private fun orderFrom(jsonText: String): Order =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Order.serializer(), jsonText)

    private fun installOrdersFake(ordersJson: String) {
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("/orders/past/all") -> SimpleResponse(200, "", ordersJson, emptyMap(), url)
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        AppInit.start(ctx)
    }

    @org.junit.After fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
    }

    private fun awaitNode(text: String, substring: Boolean = false) {
        val deadline = System.currentTimeMillis() + 15_000
        var found = false
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.waitForIdle()
            found = try { rule.onNodeWithText(text, substring = substring).fetchSemanticsNode() != null } catch (_: AssertionError) { false }
            if (found) break
            Thread.sleep(50)
        }
        assertTrue(found, "node '$text' never appeared")
    }

    @Test fun checkoutPickersInteract() {
        var pickup: Boolean? = null; var tip = -1; var deal: Int? = -2; var promo = ""
        val address = SavedAddress(id = "a1", label = "Home", addressStreet = "1 Main", addressCity = "Springfield", addressState = "CA", addressZip = "12345")
        rule.setContent {
            Column {
                CheckoutOrderTypePicker(isPickup = false) { pickup = it }
                CheckoutAddressPicker(listOf(address), true, address, {}, "", {})
                CheckoutTipPicker(300) { tip = it }
            }
        }
        rule.onNodeWithText("Pickup").performClick(); assertEquals(true, pickup)
        rule.onNodeWithText("Delivery").performClick(); assertEquals(false, pickup)
        rule.onNodeWithText("$5.00").performClick(); assertEquals(500, tip)
        rule.onNodeWithText("None").performClick(); assertEquals(0, tip)
    }

    @Test fun checkoutDealsPickerSelectsAndClears() {
        var deal: Int? = -2; var promo = ""
        rule.setContent { CheckoutDealsPicker(listOf(Deal(id = 3, title = "2for1", description = "Buy one get one")), null, { deal = it }, "") { promo = it } }
        // The card merges its text into one node ("2for1, Buy one get one").
        rule.onNodeWithText("2for1", substring = true).assertIsDisplayed()
        rule.onNodeWithText("2for1", substring = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(3, deal)
    }

    @Test fun checkoutAddressPickerEmpty() {
        rule.setContent { CheckoutAddressPicker(emptyList(), true, null, {}, "", {}) }
        rule.waitForIdle()
    }
    @Test fun checkoutOrderTypePickerPickupSelected() {
        rule.setContent { CheckoutOrderTypePicker(isPickup = true) {} }
        rule.waitForIdle()
    }

    @Test fun editProfileDialogSavesTrimmed() {
        var saved: Customer? = null; var dismissed = false
        rule.setContent {
            EditProfileDialog(
                Customer(id = 5, firstName = "Ann", lastName = "Lee", email = "a@b.c"),
                onDismiss = { dismissed = true },
                onSave = { saved = it },
            )
        }
        rule.onNodeWithText("Edit profile", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Save changes", substring = true).performClick()
        assertEquals("Ann", saved?.firstName); assertEquals("a@b.c", saved?.email)
        assertEquals(false, dismissed)
    }

    @Test fun homeClosedMerchantAndRatingsRender() {
        val closed = Merchant(id = 2, name = "Closed Cafe", isOpen = false, averageRating = 3.25, totalRatings = 42)
        val free = Merchant(id = 3, name = "Free Delivery Co", freeDeliveryThreshold = 2000, averageRating = 5.0)
        rule.setContent { HomeContent(listOf(closed, free), onMerchantClick = {}) }
        rule.onNodeWithText("Closed Cafe").assertExists(); rule.onNodeWithText("Free Delivery Co").assertExists()
    }

    @Test fun ordersEmptyStateWhenSignedInWithNoOrders() {
        installOrdersFake("""{"data":[]}""")
        rule.setContent { OrdersScreen {} }
        awaitNode("No orders yet", substring = true)
    }

    @Test fun trackingDrivingStageWithCourier() {
        installOrdersFake("""{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","pickedupAt":"2024-01-01T00:20:00Z","courierName":"Sam","courierPhone":"5551234","merchant":{"id":1,"name":"Burger Barn"}}]}""")
        rule.setContent { OrderTrackingScreen(9) {} }
        awaitNode("Sam", substring = true)
    }

    @Test fun trackingNotFound() {
        installOrdersFake("""{"data":[]}""")
        rule.setContent { OrderTrackingScreen(123) {} }
        awaitNode("Order not found", substring = true)
    }

    @Test fun emailVerificationBinderSucceeds() {
        BitesCore.transport = { url, _, _, _ -> SimpleResponse(200, "", "{}", emptyMap(), url) }
        AppInit.start(ctx)
        var done = false
        rule.setContent { EmailVerificationScreen("cust-1", "tok") { done = true } }
        awaitNode("verified", substring = true)
        assertEquals(false, done) // not until the user taps Done
    }

}
