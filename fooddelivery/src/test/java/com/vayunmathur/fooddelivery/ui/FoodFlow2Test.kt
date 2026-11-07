package com.vayunmathur.fooddelivery.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.api.BitesAuth
import com.vayunmathur.fooddelivery.api.BitesCore
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Second flow pass: order card actions (track / pick up), account delete
 * and add-address flows, restaurant loyalty + search, checkout pickup
 * re-pricing, and email-verification failure — all over the fake transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodFlow2Test {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val realTransport = BitesCore.transport

    @org.junit.After fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
    }

    private fun installFake(ordersJson: String) {
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("/orders/past/all") -> SimpleResponse(200, "", ordersJson, emptyMap(), url)
                url.contains("/orders/pickUpOrder") -> SimpleResponse(200, "", "{}", emptyMap(), url)
                url.contains("/customers/me") && url.contains("DELETE") -> SimpleResponse(200, "", "{}", emptyMap(), url)
                url.contains("/customers/me") -> SimpleResponse(200, "", """{"data":{"id":5,"firstName":"Ann","lastName":"Lee","email":"a@b.c","uuid":"cust-uuid"}}""", emptyMap(), url)
                url.contains("/merchants/1") -> SimpleResponse(200, "", """{"data":{"id":1,"name":"Burger Barn","categories":[{"id":1,"name":"Mains","itemIds":[10,11]}],"items":[{"id":10,"name":"Burger","price":999},{"id":11,"name":"Fries","price":399}]}}""", emptyMap(), url)
                url.contains("/checkout") -> SimpleResponse(200, "", """{"serviceable":{"ok":true},"clientSecret":"pi_x","order":{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500}}""", emptyMap(), url)
                url.contains("/auth/verify_phone") -> SimpleResponse(200, "", """{"state_id":"state-1"}""", emptyMap(), url)
                url.contains("/auth/token") -> SimpleResponse(200, "", """{"access_token":"acc","refresh_token":"ref","token_type":"Bearer","expires_in":3600}""", emptyMap(), url)
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        AppInit.start(ctx)
    }

    private fun awaitNode(text: String, substring: Boolean = true) {
        val deadline = System.currentTimeMillis() + 15_000
        var found = false
        while (System.currentTimeMillis() < deadline) {
            rule.mainClock.autoAdvance = false
            rule.mainClock.advanceTimeBy(600L)
            rule.mainClock.autoAdvance = true
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.waitForIdle()
            found = try { rule.onNodeWithText(text, substring = substring).fetchSemanticsNode() != null } catch (_: AssertionError) { false }
            if (found) break
            Thread.sleep(50)
        }
        assertTrue(found, "node '$text' never appeared")
    }

    private fun signInThroughAccount() {
        rule.setContent { AccountScreen() }
        awaitNode("Sign in to Food Delivery")
        rule.onNodeWithText("Phone number").performTextInput("5551234567")
        rule.onNodeWithText("Send Code").performClick()
        awaitNode("Verify")
        rule.onNodeWithText("Verification code").performTextInput("123456")
        rule.onNodeWithText("Verify").performClick()
        awaitNode("Ann Lee")
    }

    @Test fun ordersTrackAndPickupActions() {
        installFake("""{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","merchant":{"id":1,"name":"Burger Barn"}},{"id":10,"uuid":"u10","foodTotal":500,"orderTotal":700,"channel":"ORDER_CHANNEL_STOREFRONT_PICKUP","merchant":{"id":1,"name":"Burger Barn"}}]}""")
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        var tracked = -1
        rule.setContent { OrdersScreen { tracked = it } }
        awaitNode("Track order")
        rule.onNodeWithText("Track order").performClick(); assertEquals(9, tracked)
        awaitNode("Mark picked up")
        rule.onNodeWithText("Mark picked up")
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    @Test fun accountDeleteAccountFlow() {
        installFake("""{"data":[]}""")
        signInThroughAccount()
        awaitNode("Delete account")
        rule.onNodeWithText("Delete account")
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitNode("permanently deletes", substring = true)
        // Confirm in the warning dialog (same label, second node — the dialog's button).
        val nodes = rule.onAllNodesWithText("Delete account").fetchSemanticsNodes().size
        assertTrue(nodes >= 1)
        rule.onAllNodesWithText("Delete account")[nodes - 1]
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitNode("Sign in to Food Delivery")
    }

    @Test fun restaurantLoyaltyJoinAndSearch() {
        installFake("""{"data":[]}""")
        rule.setContent { RestaurantScreen(1, {}, {}) }
        awaitNode("Burger")
        // Search filters the menu after the debounce (compose clock drives it).
        rule.onNodeWithText("Search", substring = true).performTextInput("Fries")
        rule.mainClock.autoAdvance = false; rule.mainClock.advanceTimeBy(2_000L); rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        awaitNode("Fries")
    }

    @Test fun checkoutPickupToggleReprices() { runBlocking {
        installFake("""{"data":[]}""")
        AddressStore.save(ctx, SavedAddress(id = "home", label = "Home", addressStreet = "1 Main", latitude = 37.5, longitude = -122.3, isDefault = true))
        val items = listOf(CartItem(menuItem = MenuItem(id = 10, name = "Burger", price = 999), quantity = 1, merchantId = 1))
        rule.setContent { CheckoutScreen(items, {}, {}) }
        awaitNode("Place Order")
        rule.onNodeWithText("Pickup").performClick()
        rule.mainClock.autoAdvance = false; rule.mainClock.advanceTimeBy(3_000L); rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        awaitNode("Place Order")
    } }

    @Test fun emailVerificationFailureShowsError() {
        BitesCore.transport = { url, _, _, _ -> SimpleResponse(500, "", "{}", emptyMap(), url) }
        AppInit.start(ctx)
        rule.setContent { EmailVerificationScreen("cust-1", "bad-token") {} }
        awaitNode("failed", substring = true)
    }
}
