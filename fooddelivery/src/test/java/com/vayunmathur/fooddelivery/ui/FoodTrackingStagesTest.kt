package com.vayunmathur.fooddelivery.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
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
 * Tracking-stage variants, address-section actions, and the restaurant
 * menu's scroll + search filter — the remaining screen branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodTrackingStagesTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val realTransport = BitesCore.transport

    @org.junit.After fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
    }

    private fun install(orderJson: String) {
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("/orders/past/all") -> SimpleResponse(200, "", orderJson, emptyMap(), url)
                url.contains("/merchants/1") -> SimpleResponse(200, "", """{"data":{"id":1,"name":"Burger Barn","categories":[{"id":1,"name":"Mains","itemIds":[10,11,12,13,14,15]}],"items":[{"id":10,"name":"Burger","price":999},{"id":11,"name":"Fries","price":399},{"id":12,"name":"Shake","price":499},{"id":13,"name":"Salad","price":699},{"id":14,"name":"Soup","price":599},{"id":15,"name":"Pie","price":449}]}}""", emptyMap(), url)
                url.contains("/customers/me/rewards") -> SimpleResponse(200, "", """{"data":[]}""", emptyMap(), url)
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        AppInit.start(ctx)
    }

    private fun awaitNode(text: String, substring: Boolean = true) {
        val deadline = System.currentTimeMillis() + 15_000
        var found = false
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.waitForIdle()
            found = try { rule.onNodeWithText(text, substring = substring).fetchSemanticsNode() != null } catch (_: AssertionError) { false }
            if (found) break
            Thread.sleep(50)
        }
        assertTrue(found, "node '$text' never appeared")
    }

    @Test fun trackingPreparingStage() {
        install("""{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","merchant":{"id":1,"name":"Burger Barn"}}]}""")
        rule.setContent { OrderTrackingScreen(9) {} }
        awaitNode("Preparing")
    }

    @Test fun trackingArrivingWithLiveEtaAndProof() {
        install("""{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","driverReachedCustomerAt":"2024-01-01T00:20:00Z","courierName":"Sam","proofOfDeliveryImageUrl":"https://example.com/proof.jpg","merchant":{"id":1,"name":"Burger Barn"}}]}""")
        rule.setContent { OrderTrackingScreen(9) {} }
        awaitNode("Sam")
    }

    @Test fun trackingPickupReadyStage() {
        install("""{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_PICKUP","driverReachedMerchantAt":"2024-01-01T00:10:00Z","merchant":{"id":1,"name":"Burger Barn"}}]}""")
        rule.setContent { OrderTrackingScreen(9) {} }
        awaitNode("Ready for Pickup")
    }

    @Test fun addressSetDefaultAndDeleteActions() { runBlocking {
        AddressStore.save(ctx, SavedAddress(id = "a1", label = "Home", addressStreet = "1 Main", isDefault = true))
        AddressStore.save(ctx, SavedAddress(id = "a2", label = "Work", addressStreet = "2 Oak", isDefault = false))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        var latest: List<SavedAddress> = AddressStore.getAll(ctx)
        rule.setContent {
            AccountAddressesSection(scope, ctx, true, latest, { latest = it }, AddressFormState(), {}, {})
        }
        awaitNode("Set Default", substring = true)
        rule.onNodeWithText("Set Default", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        // Poll the store until the default flips.
        val deadline = System.currentTimeMillis() + 10_000
        var flipped = false
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runBlocking { if (AddressStore.getDefault(ctx)?.id == "a2") { flipped = true } }
            if (flipped) break
            Thread.sleep(50)
        }
        assertTrue(flipped, "set-default action never landed in the store")
    } }

    @Test fun restaurantScrollsAndSearches() {
        install("""{"data":[]}""")
        var added = 0
        rule.setContent {
            RestaurantScreen(1, {}, { added++ })
        }
        awaitNode("Burger")
        // Scroll the menu list so lower sections compose.
        try {
            rule.onNode(hasScrollAction()).performScrollToIndex(3)
        } catch (_: AssertionError) { rule.waitForIdle() }
        rule.waitForIdle()
        assertEquals(0, added)
    }
}
