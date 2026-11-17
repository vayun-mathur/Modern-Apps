package com.vayunmathur.openassistant.util

import com.vayunmathur.library.intents.fooddelivery.CartData as FoodCartData
import com.vayunmathur.library.intents.fooddelivery.CartItemData
import com.vayunmathur.library.intents.fooddelivery.MenuData
import com.vayunmathur.library.intents.fooddelivery.MenuItemData
import com.vayunmathur.library.intents.fooddelivery.MenuModifierData
import com.vayunmathur.library.intents.fooddelivery.MenuModifierGroupData
import com.vayunmathur.library.intents.fooddelivery.OrderData as FoodOrderData
import com.vayunmathur.library.intents.fooddelivery.OrderSummaryData
import com.vayunmathur.library.intents.fooddelivery.RestaurantData
import com.vayunmathur.library.intents.shop.CartData as ShopCartData
import com.vayunmathur.library.intents.shop.CartLineData
import com.vayunmathur.library.intents.shop.CartStoreData
import com.vayunmathur.library.intents.shop.OrderData as ShopOrderData
import com.vayunmathur.library.intents.shop.ProductData
import com.vayunmathur.library.intents.shop.ProductDetailData
import com.vayunmathur.library.intents.shop.ProductVariantData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.vayunmathur.openassistant.platform.summarizeRestaurants
import com.vayunmathur.openassistant.platform.summarizeMenu
import com.vayunmathur.openassistant.platform.summarizeFoodCart
import com.vayunmathur.openassistant.platform.summarizeOrderSummary
import com.vayunmathur.openassistant.platform.summarizeFoodOrders
import com.vayunmathur.openassistant.platform.summarizeProducts
import com.vayunmathur.openassistant.platform.summarizeProductDetail
import com.vayunmathur.openassistant.platform.summarizeShopCart
import com.vayunmathur.openassistant.platform.summarizeShopOrders
import com.vayunmathur.openassistant.platform.summarizeConversations
import com.vayunmathur.openassistant.platform.summarizeConversationMessages
import com.vayunmathur.openassistant.platform.summarizeSendResult
import com.vayunmathur.openassistant.platform.summarizeDirections
import com.vayunmathur.openassistant.platform.summarizeRideEstimate
import com.vayunmathur.openassistant.platform.summarizeOrderLookup
import com.vayunmathur.openassistant.platform.parseSetCartItems

/**
 * Model-facing summaries for the food/shop tools: the exact strings the
 * model reads back. Populated, empty, provider-error and truncation
 * cases for every summarizer the new handlers call, plus the
 * food_set_cart argument parser.
 */
class SummarizeNewToolsTest {

    @Test fun restaurantsSummarizeOpenClosedAndEmpty() {
        val text = summarizeRestaurants(
            listOf(
                RestaurantData(7, "Joe's", "1 Main St", isOpen = true, rating = 4.5),
                RestaurantData(8, "Closed Cafe", "2 Oak Ave", isOpen = false),
            ),
        )
        assertTrue(text.contains("merchant_id=7 Joe's ★4.5 open"), text)
        assertTrue(text.contains("merchant_id=8 Closed Cafe closed"), text)
        assertEquals("No restaurants found", summarizeRestaurants(emptyList()))
    }

    @Test fun menuSummarizesModifiersErrorsAndTruncation() {
        val group = MenuModifierGroupData(
            1, "Toppings", required = true, minSelections = 1, maxSelections = 2,
            modifiers = listOf(MenuModifierData(5, "Extra cheese", 150)),
        )
        val items = (1..30).map { MenuItemData(it, "Item $it", 100 * it, available = it % 2 == 0, "Mains", listOf(group)) }
        val text = summarizeMenu(MenuData(7, "Joe's", items = items))
        assertTrue(text.startsWith("Menu Joe's (merchant_id=7):"), text)
        assertTrue(text.contains("Toppings[5:Extra cheese] req=true min=1 max=2"), text)
        assertTrue(text.contains("(6 more items not shown)"), text)
        assertEquals(
            "Error: menu unavailable (MERCHANT_NOT_FOUND)",
            summarizeMenu(MenuData(error = "MERCHANT_NOT_FOUND")),
        )
        assertEquals("No menu items for Joe's", summarizeMenu(MenuData(7, "Joe's")))
    }

    @Test fun foodCartOrderSummaryAndOrdersSummarize() {
        val line = CartItemData(3, "Margherita", 2, listOf(5), 2598)
        val cart = summarizeFoodCart(FoodCartData(7, "Joe's", listOf(line), 2598))
        assertTrue(cart.contains("item_id=3 Margherita x2 2598c"), cart)
        assertTrue(cart.contains("Total 2598c"), cart)
        assertEquals("Cart is empty", summarizeFoodCart(FoodCartData(7, "Joe's")))
        assertEquals(
            "Error: cart not updated (ITEM_UNAVAILABLE)",
            summarizeFoodCart(FoodCartData(error = "ITEM_UNAVAILABLE")),
        )

        val summary = summarizeOrderSummary(
            OrderSummaryData(
                merchantId = 7, merchantName = "Joe's", items = listOf(line),
                subtotalCents = 2598, tipCents = 200, totalCents = 2798,
            ),
        )
        assertTrue(summary.contains("delivery") && summary.contains("total=2798c"), summary)
        assertTrue(summary.contains("checkout=fooddelivery://checkout"), summary)
        assertEquals(
            "Error: order not priced (EMPTY_CART)",
            summarizeOrderSummary(OrderSummaryData(error = "EMPTY_CART")),
        )

        val orders = summarizeFoodOrders(
            listOf(FoodOrderData(9, "u-1", 7, "Joe's", "delivered", 2598, 2, "2026-11-16", isDelivery = true, isDone = true)),
        )
        assertTrue(orders.contains("order_id=9 Joe's delivered 2598c items=2"), orders)
        assertEquals("No past orders", summarizeFoodOrders(emptyList()))
        // The cap drops later orders without a marker of its own.
        val many = (1..15).map { FoodOrderData(it, null, 7, "Joe's", "done", 1, 1) }
        assertEquals(10, summarizeFoodOrders(many).lines().size)
    }

    @Test fun parseSetCartItemsAcceptsOnlyTheDocumentedShape() {
        assertEquals(
            listOf(
                com.vayunmathur.library.intents.fooddelivery.SetCartItem(1, 2, listOf(5, 6)),
                com.vayunmathur.library.intents.fooddelivery.SetCartItem(2, 1, emptyList()),
            ),
            parseSetCartItems("""[{"itemId":1,"qty":2,"modifierIds":[5,6]},{"itemId":2,"qty":1}]"""),
        )
        assertNull(parseSetCartItems("not json"))
        assertNull(parseSetCartItems("""{"itemId":1}"""))
        assertNull(parseSetCartItems("""[{"itemId":"x","qty":1}]"""))
        assertNull(parseSetCartItems("""[{"qty":1}]"""))
        assertEquals(emptyList(), parseSetCartItems("[]"))
    }

    @Test fun productsDetailAndShopCartSummarize() {
        val products = summarizeProducts(
            listOf(ProductData("Enamel Mug", "p1", "v1", "s.example", "Store", 1200, "USD", "", checkoutEligible = true)),
        )
        assertTrue(products.contains("variant_id=v1 product_id=p1 Enamel Mug 1200 USD @ Store (s.example) checkout=true"), products)
        assertEquals("No products found", summarizeProducts(emptyList()))

        val detail = summarizeProductDetail(
            ProductDetailData(
                title = "Enamel Mug", shopDomain = "s.example", storeName = "Store", price = 1200,
                variants = listOf(ProductVariantData("v1", "Blue", 1200, "USD", available = true)),
            ),
        )
        assertTrue(detail.contains("variant_id=v1 Blue 1200 USD available"), detail)
        assertEquals(
            "Error: product unavailable (BACKEND_FAILED)",
            summarizeProductDetail(ProductDetailData(error = "BACKEND_FAILED")),
        )

        val line = CartLineData("Enamel Mug", "Blue", "v1", "s.example", "Store", 1200, "USD", 2)
        val cart = summarizeShopCart(
            ShopCartData(listOf(CartStoreData("s.example", "Store", listOf(line))), totalItems = 2),
        )
        assertTrue(cart.contains("Store Store (s.example):"), cart)
        assertTrue(cart.contains("variant_id=v1 Enamel Mug Blue x2 1200 USD"), cart)
        assertEquals("Shop cart is empty", summarizeShopCart(ShopCartData()))
        assertEquals(
            "Error: cart not updated (INVALID_QUANTITY)",
            summarizeShopCart(ShopCartData(error = "INVALID_QUANTITY")),
        )

        val orders = summarizeShopOrders(
            listOf(ShopOrderData("o1", "c1", "s.example", "Store", "pending", 2400, "USD", 2, 99L)),
        )
        assertTrue(orders.contains("order_id=o1 Store pending 2400 USD items=2"), orders)
        assertEquals("No shop orders", summarizeShopOrders(emptyList()))
    }
}
