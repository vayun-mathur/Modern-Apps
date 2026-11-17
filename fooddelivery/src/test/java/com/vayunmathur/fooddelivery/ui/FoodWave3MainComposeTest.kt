package com.vayunmathur.fooddelivery.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.MainActivity
import com.vayunmathur.fooddelivery.network.BitesAuth
import com.vayunmathur.fooddelivery.network.BitesCore
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.library.network.SimpleResponse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/**
 * Wave-3: the real MainActivity composition — tab pager, navigation entries,
 * cart-restore and the cross-merchant pending-cart dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodWave3MainComposeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val realTransport = BitesCore.transport

    init {
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("/merchants/all/stores") -> SimpleResponse(
                    200, "",
                    """{"data":{"merchants":[{"id":7,"name":"Burger Barn","latitude":37.0,"longitude":-122.0}]}}""",
                    emptyMap(), url,
                )
                url.contains("/merchants/7") -> SimpleResponse(
                    200, "",
                    """{"data":{"id":7,"name":"Burger Barn","categories":[{"id":1,"name":"Mains","itemIds":[10]}],"items":[{"id":10,"name":"Burger","price":999}]}}""",
                    emptyMap(), url,
                )
                url.contains("/checkout") -> SimpleResponse(
                    200, "",
                    """{"data":{"clientSecret":"pi_secret","order":{"id":11,"uuid":"u11","foodTotal":999,"taxes":80},"serviceable":{"ok":true}}}""",
                    emptyMap(), url,
                )
                url.contains("/orders/past/all") -> SimpleResponse(
                    200, "",
                    """{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","merchant":{"id":7,"name":"Burger Barn"}}]}""",
                    emptyMap(), url,
                )
                url.contains("/orders/u11/rewards") -> SimpleResponse(
                    200, "", """{"data":{"rewardsAvailable":0,"rewardsRate":0.0}}""", emptyMap(), url,
                )
                url.contains("/customers/me/rewards") -> SimpleResponse(200, "", """{"data":[]}""", emptyMap(), url)
                url.contains("/customers/me") -> SimpleResponse(
                    200, "", """{"data":{"id":5,"uuid":"cu1","firstName":"Ann","lastName":"Lee","email":"a@b.c"}}""",
                    emptyMap(), url,
                )
                url.contains("/deals") -> SimpleResponse(
                    200, "", """{"data":[{"id":1,"title":"Main Deal","merchantId":7,"isActive":true}]}""",
                    emptyMap(), url,
                )
                url.contains("/orders/me/savings") -> SimpleResponse(
                    200, "", """{"data":{"customerSavings":500,"orderCount":2}}""", emptyMap(), url,
                )
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        runBlocking {
            AddressStore.save(
                ctx,
                SavedAddress(
                    id = "home", label = "Home", addressStreet = "1 Main",
                    latitude = 37.0, longitude = -122.0, isDefault = true,
                ),
            )
        }
        // A cart from a different merchant (99), so adding from merchant 7
        // must go through the pending-cart dialog. Seeded synchronously in
        // the store's own prefs shape (CartStore.save is fire-and-forget).
        ctx.getSharedPreferences("fooddelivery_cart", Context.MODE_PRIVATE).edit()
            .putString(
                "cart_items_v2",
                """[{"menuItem":{"id":1,"name":"Seed Burger","price":500},"quantity":1,"merchantId":99,"merchantName":"Other Place","lineId":"seed-1"}]""",
            ).commit()
    }

    @After fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
    }

    private fun awaitNode(text: String, substring: Boolean = true) {
        val deadline = System.currentTimeMillis() + 20_000
        var found = false
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.waitForIdle()
            found = try {
                rule.onNodeWithText(text, substring = substring).fetchSemanticsNode() != null
            } catch (_: AssertionError) { false }
            if (found) break
            Thread.sleep(50)
        }
        assertTrue(found, "node '$text' never appeared")
    }

    @Test fun tabsWalkComposesEveryTab() {
        awaitNode("Burger Barn")
        rule.onNodeWithText("Cart").performClick()
        awaitNode("Seed Burger")
        rule.onNodeWithText("Deals").performClick()
        awaitNode("Main Deal")
        rule.onNodeWithText("Orders").performClick()
        awaitNode("Track order")
        rule.onNodeWithText("Account").performClick()
        awaitNode("Ann Lee")
    }

    @Test fun pendingCartDialogReplacesCartOnConfirm() {
        awaitNode("Burger Barn")
        rule.onNodeWithText("Burger Barn").performClick()
        awaitNode("Burger Barn")
        // Scroll the menu list so the Burger row composes.
        rule.onNode(hasScrollAction()).performScrollToIndex(5)
        rule.waitForIdle()
        // The menu row's add button: the clickable node on the same row as,
        // and to the right of, the item name (it carries no text of its own).
        val burgerBounds = rule.onNodeWithText("Burger", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val clickables = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
        val nodes = clickables.fetchSemanticsNodes()
        val addIndex = nodes.indexOfFirst { node ->
            val b = node.boundsInRoot
            kotlin.math.abs((b.top + b.bottom) / 2 - (burgerBounds.top + burgerBounds.bottom) / 2) < 80 &&
                b.left > burgerBounds.right
        }
        assertTrue(addIndex >= 0, "no clickable node on the Burger row")
        clickables[addIndex].performClick()
        awaitNode("Start a new cart?")
        rule.onNodeWithText("Start new cart").performClick()
        // Back out to the tabs and open the cart: Burger is the cart now.
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithText("Cart").performClick()
        // The cart now holds the Burger line, not the seeded Other Place line.
        awaitNode("Checkout")
        rule.onNodeWithText("Seed Burger").assertDoesNotExist()
        rule.onNodeWithText("1x Burger").fetchSemanticsNode()
    }

    @Test fun cartTabCheckoutEntryComposesCheckout() {
        rule.onNodeWithText("Cart").performClick()
        awaitNode("Seed Burger")
        rule.onNodeWithText("Checkout").performClick()
        awaitNode("Place Order")
    }

    @Test fun ordersTabTrackEntryComposesTracking() {
        rule.onNodeWithText("Orders").performClick()
        awaitNode("Track order")
        rule.onNodeWithText("Track order").performClick()
        awaitNode("Preparing")
    }
}
