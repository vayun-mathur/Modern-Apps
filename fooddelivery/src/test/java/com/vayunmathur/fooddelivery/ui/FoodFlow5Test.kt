package com.vayunmathur.fooddelivery.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.api.BitesAuth
import com.vayunmathur.fooddelivery.api.BitesCore
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.Modifier
import com.vayunmathur.fooddelivery.data.ModifierGroup
import com.vayunmathur.fooddelivery.data.RemoteImageFetchers
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.data.SelectedModifier
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fifth pass: menu item rows, cart modifier editing, address delete,
 * tracking stage headers with live ETA, and the remote image fetcher's
 * scheme guards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodFlow5Test {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val realTransport = BitesCore.transport

    @org.junit.After fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
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

    private fun installFake(ordersJson: String) {
        BitesCore.transport = { url, _, _, _ ->
            if (url.contains("/orders/past/all")) SimpleResponse(200, "", ordersJson, emptyMap(), url)
            else SimpleResponse(404, "", "{}", emptyMap(), url)
        }
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        AppInit.start(ctx)
    }

    @Test fun menuItemRowVariantsAndAdd() {
        var adds = 0
        val plain = MenuItem(id = 1, name = "Plain", price = 500)
        val compared = MenuItem(id = 2, name = "Compared", price = 500, doordashPrice = 900, ubereatsPrice = 800,
            modifierGroups = listOf(ModifierGroup(id = 1, name = "Extras", modifiers = listOf(Modifier(id = 1, name = "X", price = 1)))))
        val withImage = MenuItem(id = 3, name = "Photo Dish", price = 700, imageUrl = "https://example.com/dish.png")
        rule.setContent {
            Column {
                MenuItemRow(plain) { adds++ }
                MenuItemRow(compared) { adds++ }
                MenuItemRow(withImage) { adds++ }
            }
        }
        rule.onNodeWithText("Plain").assertExists()
        rule.onNodeWithText("on DD", substring = true).assertExists()
        rule.onNodeWithText("Customizable", substring = true).assertExists()
        // Invoke every row's add action directly (icon buttons are text-less).
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        assertTrue(clickables >= 3)
        for (i in 0 until clickables) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performSemanticsAction(SemanticsActions.OnClick) }
        }
        assertTrue(adds >= 1, "no add action fired; adds=$adds")
    }

    @Test fun cartEditModifiersThroughDialog() {
        var edited: List<SelectedModifier>? = null
        val grouped = MenuItem(id = 10, name = "Burger", price = 999, modifierGroups = listOf(
            ModifierGroup(id = 1, name = "Extras", required = true, minSelections = 1, maxSelections = 2,
                modifiers = listOf(Modifier(id = 100, name = "Cheese", price = 100), Modifier(id = 101, name = "Bacon", price = 200))),
        ))
        val items = listOf(CartItem(menuItem = grouped, quantity = 1, selectedModifiers = listOf(SelectedModifier(1, 100, "Cheese", 100))))
        rule.setContent { CartScreen(items, {}, {}, { _, mods -> edited = mods }) }
        rule.onNodeWithText("1x Burger").assertExists()
        // Open the editor via the line's edit action, then save from the dialog.
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until clickables) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performSemanticsAction(SemanticsActions.OnClick) }
            if (try { rule.onNodeWithText("Extras").fetchSemanticsNode() != null } catch (_: AssertionError) { false }) break
        }
        awaitNode("Extras")
        rule.onNodeWithText("Save changes", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(edited != null, "modifier edit was never confirmed")
    }

    @Test fun addressDeleteRemovesFromStore() { runBlocking {
        AddressStore.save(ctx, SavedAddress(id = "a1", label = "Home", addressStreet = "1 Main", isDefault = true))
        AddressStore.save(ctx, SavedAddress(id = "a2", label = "Work", addressStreet = "2 Oak"))
        val scope = CoroutineScope(Dispatchers.Main)
        var latest: List<SavedAddress> = AddressStore.getAll(ctx)
        rule.setContent {
            AccountAddressesSection(scope, ctx, true, latest, { latest = it }, AddressFormState(), {}, {})
        }
        awaitNode("Work")
        // Click every clickable node (set-default, edit, delete are text-less or text buttons);
        // then poll the store until a2 is gone or the deadline passes.
        val deadline = System.currentTimeMillis() + 10_000
        var deleted = false
        while (System.currentTimeMillis() < deadline && !deleted) {
            val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
            for (i in 0 until clickables) {
                runCatching { rule.onAllNodes(hasClickAction())[i].performSemanticsAction(SemanticsActions.OnClick) }
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            deleted = AddressStore.getAll(ctx).none { it.id == "a2" }
            if (!deleted) Thread.sleep(100)
        }
        assertTrue(deleted, "delete action never removed the address")
    } }

    @Test fun trackingArrivingWithFutureLiveEta() {
        installFake("""{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","driverReachedCustomerAt":"2024-01-01T00:20:00Z","liveDeliveryEta":"2099-01-01T00:00:00Z","courierName":"Sam","courierPhone":"5551234","merchant":{"id":1,"name":"Burger Barn"}}]}""")
        rule.setContent { OrderTrackingScreen(9) {} }
        awaitNode("Arriving Soon")
        awaitNode("Sam")
    }

    @Test fun trackingPreparingSoonWithFarDueDate() {
        installFake("""{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","dueAt":"2099-01-01T00:00:00Z","merchant":{"id":1,"name":"Burger Barn"}}]}""")
        rule.setContent { OrderTrackingScreen(9) {} }
        awaitNode("Preparing Soon")
    }

    @Test fun remoteImageFetcherSchemeGuards() { runBlocking {
        assertNull(RemoteImageFetchers.network(null, ctx))
        assertNull(RemoteImageFetchers.network(42, ctx))
        assertNull(RemoteImageFetchers.network("ftp://example.com/x.png", ctx))
        assertNull(RemoteImageFetchers.network(Uri.parse("file:///tmp/x.png"), ctx))
        // Loopback refusal is deterministic on any machine (no external network).
        val refused = runCatching { RemoteImageFetchers.network("http://127.0.0.1:9/x.png", ctx) }
            .exceptionOrNull()
        assertTrue(refused is java.io.IOException, "expected loopback refusal, got $refused")
    } }
}
