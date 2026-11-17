package com.vayunmathur.fooddelivery.intents

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.network.BitesAuth
import com.vayunmathur.fooddelivery.network.BitesCore
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.platform.isCheckoutDeepLink
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.intents.fooddelivery.FindRestaurants
import com.vayunmathur.library.intents.fooddelivery.GetCart
import com.vayunmathur.library.intents.fooddelivery.GetMenu
import com.vayunmathur.library.intents.fooddelivery.GetOrders
import com.vayunmathur.library.intents.fooddelivery.PrepareOrder
import com.vayunmathur.library.intents.fooddelivery.SetCart
import com.vayunmathur.library.intents.fooddelivery.SetCartItem
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assistant provider intents ([FindRestaurantsIntent] … [GetOrdersIntent])
 * against the existing Bites fake-transport harness (see
 * `BitesApiFakeTransportTest`): canned envelopes per URL, recorded requests,
 * the real [CartStore]/[AddressStore] under Robolectric. Validation failures
 * are asserted to leave the stored cart untouched, and no test — in
 * particular [PrepareOrderIntent] — may ever hit a checkout endpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AssistantIntentsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val realTransport = BitesCore.transport
    private val requestedUrls = mutableListOf<String>()

    private fun resp(url: String, body: String) = SimpleResponse(200, "", body, emptyMap(), url)

    @Before
    fun installFakeTransport() {
        requestedUrls.clear()
        BitesCore.transport = { url, _, _, _ ->
            requestedUrls += url
            when {
                url.contains("/merchants/all/stores") -> resp(
                    url,
                    """{"data":{"merchants":[
                      {"id":7,"name":"Joe's Cafe","latitude":37.0,"longitude":-122.0,
                       "isOpen":true,"merchantTags":["burgers","cafe"]},
                      {"id":8,"name":"Sushi Place","latitude":37.01,"longitude":-122.0,
                       "isOpen":true,"merchantTags":["sushi"]}
                    ]}}""",
                )
                url.contains("/merchants/7") -> resp(
                    url,
                    """{"data":{"id":7,"name":"Joe's Cafe",
                      "categories":[{"id":1,"name":"Mains","itemIds":[10,11]}],
                      "items":[
                        {"id":10,"name":"Burger","price":1000,"isAvailable":true,"isInStock":true,
                         "modifierGroups":[
                           {"id":1,"name":"Size","required":true,"minSelections":1,"maxSelections":1,
                            "modifiers":[{"id":101,"name":"Small","price":0},
                                         {"id":102,"name":"Large","price":200}]},
                           {"id":2,"name":"Extras","required":false,"minSelections":0,"maxSelections":2,
                            "modifiers":[{"id":201,"name":"Cheese","price":100},
                                         {"id":202,"name":"Bacon","price":150},
                                         {"id":203,"name":"Avocado","price":120}]}
                         ]},
                        {"id":11,"name":"Salad","price":800,"isAvailable":true,"isInStock":true},
                        {"id":12,"name":"Sold Out Soup","price":600,"isAvailable":false,"isInStock":true}
                      ]}}""",
                )
                url.contains("/merchants/8") -> resp(
                    url,
                    """{"data":{"id":8,"name":"Sushi Place","categories":[],
                      "items":[{"id":20,"name":"Roll","price":1200,"isAvailable":true,"isInStock":true}]}}""",
                )
                url.contains("/orders/past/all") -> resp(
                    url,
                    """{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"deliveryFee":200,
                      "taxes":80,"tips":100,"orderTotal":1380,
                      "merchant":{"id":7,"name":"Joe's Cafe"},
                      "orderItems":[{"id":1,"name":"Burger","quantity":1,"price":1000}],
                      "channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY",
                      "deliveredAt":"2024-01-01T00:00:00Z"}]}""",
                )
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        AppInit.start(ctx)
        runBlocking {
            CartStore.clear(ctx)
            awaitCart { it.isEmpty() }
        }
    }

    @After
    fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
        foodAssistantBackendForTest = null
    }

    /** CartStore writes are async; poll the store until [predicate] holds. */
    private suspend fun awaitCart(predicate: (List<com.vayunmathur.fooddelivery.data.CartItem>) -> Boolean) {
        repeat(100) {
            if (predicate(CartStore.getAll(ctx))) return
            Thread.sleep(20)
        }
        assertTrue(predicate(CartStore.getAll(ctx)), "cart did not reach expected state")
    }

    private fun <T : com.vayunmathur.library.util.AssistantIntent<*, *>> activity(type: Class<T>): T =
        Robolectric.buildActivity(type).create().get()

    // --- FindRestaurants / GetMenu / GetOrders over the fake transport ---

    @Test
    fun findRestaurantsFiltersByQueryAndCapsLimit() = runBlocking {
        val intent = activity(FindRestaurantsIntent::class.java)
        val all = intent.performCalculation(FindRestaurants(lat = 37.0, lng = -122.0))
        assertEquals(listOf(7, 8), all.map { it.id })
        assertEquals("Joe's Cafe", all[0].name)
        assertTrue(all[0].isOpen)
        val byName = intent.performCalculation(FindRestaurants(query = "sushi", lat = 37.0, lng = -122.0))
        assertEquals(listOf(8), byName.map { it.id })
        val byTag = intent.performCalculation(FindRestaurants(query = "BURGERS", lat = 37.0, lng = -122.0))
        assertEquals(listOf(7), byTag.map { it.id })
        assertEquals(1, intent.performCalculation(FindRestaurants(lat = 37.0, lng = -122.0, limit = 1)).size)
        // limit <= 0 falls back to the default window.
        assertEquals(2, intent.performCalculation(FindRestaurants(lat = 37.0, lng = -122.0, limit = 0)).size)
    }

    @Test
    fun getMenuMapsCategoriesItemsAndModifierSummaries() = runBlocking {
        val intent = activity(GetMenuIntent::class.java)
        val menu = intent.performCalculation(GetMenu(merchantId = 7))
        assertNull(menu.error)
        assertEquals("Joe's Cafe", menu.merchantName)
        assertEquals(listOf(10, 11), menu.categories.single().itemIds)
        val burger = menu.items.first { it.id == 10 }
        assertEquals(1000, burger.priceCents)
        assertTrue(burger.available)
        val size = burger.modifierGroups.first { it.id == 1 }
        assertTrue(size.required)
        assertEquals(1, size.minSelections)
        assertEquals(1, size.maxSelections)
        assertEquals(listOf(101, 102), size.modifiers.map { it.id })
        assertFalse(menu.items.first { it.id == 12 }.available)
        assertEquals(ERROR_INVALID_MERCHANT, intent.performCalculation(GetMenu(merchantId = 0)).error)
        assertEquals(ERROR_MERCHANT_NOT_FOUND, intent.performCalculation(GetMenu(merchantId = 99)).error)
    }

    @Test
    fun getOrdersMapsPastOrders() = runBlocking {
        val orders = activity(GetOrdersIntent::class.java).performCalculation(GetOrders())
        val order = orders.single()
        assertEquals(9, order.id)
        assertEquals("Joe's Cafe", order.merchantName)
        assertEquals(1380, order.totalCents)
        assertEquals(1, order.itemCount)
        assertTrue(order.isDelivery)
        assertTrue(order.isDone)
    }

    // --- Cart: validated atomic replacement ---

    private suspend fun seedValidCart() {
        val result = activity(SetCartIntent::class.java).performCalculation(
            SetCart(
                merchantId = 7,
                items = listOf(SetCartItem(itemId = 10, qty = 2, modifierIds = listOf(102, 201))),
            ),
        )
        assertNull(result.error, "seed cart rejected: $result")
        // (1000 + 200 + 100) * 2
        assertEquals(2600, result.totalCents)
        awaitCart { it.size == 1 && it[0].quantity == 2 }
    }

    @Test
    fun setCartWritesValidatedCartAndGetCartReadsItBack() = runBlocking {
        seedValidCart()
        val cart = activity(GetCartIntent::class.java).performCalculation(GetCart())
        assertNull(cart.error)
        assertEquals(7, cart.merchantId)
        assertEquals("Joe's Cafe", cart.merchantName)
        val line = cart.items.single()
        assertEquals(10, line.itemId)
        assertEquals("Burger", line.name)
        assertEquals(2, line.qty)
        assertEquals(listOf(102, 201), line.modifierIds)
        assertEquals(2600, line.lineTotalCents)
        assertEquals(2600, cart.totalCents)
    }

    @Test
    fun setCartDifferentMerchantReplacesAndEmptyClears() = runBlocking {
        seedValidCart()
        // Single-merchant rule: writing a different merchant's items replaces
        // the previous cart wholesale (documented on SetCart), never merges.
        val replaced = activity(SetCartIntent::class.java).performCalculation(
            SetCart(merchantId = 8, items = listOf(SetCartItem(itemId = 20, qty = 1))),
        )
        assertNull(replaced.error)
        assertEquals(8, replaced.merchantId)
        assertEquals(listOf(20), replaced.items.map { it.itemId })
        awaitCart { it.size == 1 && it[0].merchantId == 8 && it[0].menuItem.id == 20 }
        val cleared = activity(SetCartIntent::class.java).performCalculation(
            SetCart(merchantId = 7, items = emptyList()),
        )
        assertNull(cleared.error)
        assertEquals(0, cleared.merchantId)
        assertTrue(cleared.items.isEmpty())
        awaitCart { it.isEmpty() }
    }

    @Test
    fun setCartValidationFailuresLeaveCartUntouched() = runBlocking {
        seedValidCart()
        val intent = activity(SetCartIntent::class.java)
        fun rejected(input: SetCart) = runBlocking { intent.performCalculation(input) }

        assertEquals(
            ERROR_ITEM_NOT_FOUND,
            rejected(SetCart(7, listOf(SetCartItem(itemId = 999, qty = 1)))).error,
        )
        assertEquals(
            ERROR_ITEM_UNAVAILABLE,
            rejected(SetCart(7, listOf(SetCartItem(itemId = 12, qty = 1)))).error,
        )
        assertEquals(
            ERROR_INVALID_QUANTITY,
            rejected(SetCart(7, listOf(SetCartItem(itemId = 11, qty = 0)))).error,
        )
        assertEquals(
            ERROR_INVALID_QUANTITY,
            rejected(SetCart(7, listOf(SetCartItem(itemId = 11, qty = 100)))).error,
        )
        assertEquals(
            ERROR_UNKNOWN_MODIFIER,
            rejected(SetCart(7, listOf(SetCartItem(itemId = 10, qty = 1, modifierIds = listOf(101, 999))))).error,
        )
        // Required "Size" group with no selection.
        assertEquals(
            ERROR_MODIFIER_REQUIRED,
            rejected(SetCart(7, listOf(SetCartItem(itemId = 10, qty = 1)))).error,
        )
        // Two selections in the max-1 "Size" group.
        assertEquals(
            ERROR_MODIFIER_SELECTIONS,
            rejected(SetCart(7, listOf(SetCartItem(itemId = 10, qty = 1, modifierIds = listOf(101, 102))))).error,
        )
        // Three selections in the max-2 "Extras" group.
        assertEquals(
            ERROR_MODIFIER_SELECTIONS,
            rejected(
                SetCart(7, listOf(SetCartItem(itemId = 10, qty = 1, modifierIds = listOf(101, 201, 202, 203)))),
            ).error,
        )
        assertEquals(
            ERROR_INVALID_MERCHANT,
            rejected(SetCart(0, listOf(SetCartItem(itemId = 11, qty = 1)))).error,
        )
        assertEquals(
            ERROR_MERCHANT_NOT_FOUND,
            rejected(SetCart(99, listOf(SetCartItem(itemId = 11, qty = 1)))).error,
        )
        // Every rejection returned the untouched cart and wrote nothing.
        val cart = activity(GetCartIntent::class.java).performCalculation(GetCart())
        assertEquals(2600, cart.totalCents)
        assertEquals(listOf(10), cart.items.map { it.itemId })
        awaitCart { it.size == 1 && it[0].menuItem.id == 10 && it[0].quantity == 2 }
    }

    // --- PrepareOrder: totals only, never an order ---

    @Test
    fun prepareOrderComputesKnownTotalsWithoutCheckoutCall() = runBlocking {
        seedValidCart()
        AddressStore.save(ctx, SavedAddress(id = "addr1", addressStreet = "1 Main St"))
        val intent = activity(PrepareOrderIntent::class.java)

        val pickup = intent.performCalculation(
            PrepareOrder(merchantId = 7, isPickup = true, tipCents = 300),
        )
        assertNull(pickup.error)
        assertEquals(2600, pickup.subtotalCents)
        assertEquals(0, pickup.deliveryFeeCents)
        assertNull(pickup.taxesCents)
        assertEquals(2900, pickup.totalCents)
        assertEquals("fooddelivery://checkout", pickup.checkoutDeepLink)

        val delivery = intent.performCalculation(
            PrepareOrder(merchantId = 7, isPickup = false, addressId = "addr1", tipCents = 300, promo = " SAVE "),
        )
        assertNull(delivery.error)
        // No checkout-preview endpoint exists: delivery fee / taxes unknown.
        assertNull(delivery.deliveryFeeCents)
        assertNull(delivery.taxesCents)
        assertEquals("SAVE", delivery.promo)
        assertEquals(2900, delivery.totalCents)

        assertEquals(
            ERROR_MERCHANT_MISMATCH,
            intent.performCalculation(PrepareOrder(merchantId = 8, isPickup = true)).error,
        )
        assertEquals(
            ERROR_INVALID_TIP,
            intent.performCalculation(PrepareOrder(merchantId = 7, isPickup = true, tipCents = -1)).error,
        )
        assertEquals(
            ERROR_UNKNOWN_ADDRESS,
            intent.performCalculation(PrepareOrder(merchantId = 7, addressId = "nope")).error,
        )
        // The assistant NEVER creates an order.
        assertTrue(requestedUrls.none { it.contains("/checkout") }, "checkout called: $requestedUrls")
    }

    @Test
    fun prepareOrderEmptyCartErrorsWithoutCheckoutCall() = runBlocking {
        val summary = activity(PrepareOrderIntent::class.java)
            .performCalculation(PrepareOrder(merchantId = 7, isPickup = true))
        assertEquals(ERROR_EMPTY_CART, summary.error)
        assertTrue(requestedUrls.none { it.contains("/checkout") })
    }

    // --- Deep link + DTO compat ---

    @Test
    fun checkoutDeepLinkParsesOnlyFooddeliveryCheckout() {
        fun intent(uri: String?) = Intent(ctx, com.vayunmathur.fooddelivery.MainActivity::class.java)
            .apply { if (uri != null) data = Uri.parse(uri) }
        assertTrue(intent("fooddelivery://checkout").isCheckoutDeepLink())
        assertFalse(intent("fooddelivery://restaurant/7").isCheckoutDeepLink())
        assertFalse(intent("https://example.com/checkout").isCheckoutDeepLink())
        assertFalse(intent(null).isCheckoutDeepLink())
    }

    @Test
    fun dtoDefaultsDecodeFromEmptyJson() {
        assertEquals(FindRestaurants(), Json.decodeFromString(FindRestaurants.serializer(), "{}"))
        assertEquals(GetMenu(), Json.decodeFromString(GetMenu.serializer(), "{}"))
        assertEquals(GetCart(), Json.decodeFromString(GetCart.serializer(), "{}"))
        assertEquals(SetCart(), Json.decodeFromString(SetCart.serializer(), "{}"))
        assertEquals(PrepareOrder(), Json.decodeFromString(PrepareOrder.serializer(), "{}"))
        assertEquals(GetOrders(), Json.decodeFromString(GetOrders.serializer(), "{}"))
    }
}
