package com.vayunmathur.library.intents

import com.vayunmathur.library.intents.communicate.ConversationData
import com.vayunmathur.library.intents.communicate.ConversationMessageData
import com.vayunmathur.library.intents.communicate.FindConversationsQuery
import com.vayunmathur.library.intents.communicate.GetMessagesQuery
import com.vayunmathur.library.intents.communicate.SendMessageRequest
import com.vayunmathur.library.intents.communicate.SendMessageResult
import com.vayunmathur.library.intents.fooddelivery.CartData
import com.vayunmathur.library.intents.fooddelivery.CartItemData
import com.vayunmathur.library.intents.fooddelivery.FindRestaurants
import com.vayunmathur.library.intents.fooddelivery.GetCart
import com.vayunmathur.library.intents.fooddelivery.GetMenu
import com.vayunmathur.library.intents.fooddelivery.GetOrders
import com.vayunmathur.library.intents.fooddelivery.MenuCategoryData
import com.vayunmathur.library.intents.fooddelivery.MenuData
import com.vayunmathur.library.intents.fooddelivery.MenuItemData
import com.vayunmathur.library.intents.fooddelivery.MenuModifierData
import com.vayunmathur.library.intents.fooddelivery.MenuModifierGroupData
import com.vayunmathur.library.intents.fooddelivery.OrderData
import com.vayunmathur.library.intents.fooddelivery.OrderSummaryData
import com.vayunmathur.library.intents.fooddelivery.PrepareOrder
import com.vayunmathur.library.intents.fooddelivery.RestaurantData
import com.vayunmathur.library.intents.fooddelivery.SetCart
import com.vayunmathur.library.intents.fooddelivery.SetCartItem
import com.vayunmathur.library.intents.shop.AddToCartRequest
import com.vayunmathur.library.intents.shop.CartLineData
import com.vayunmathur.library.intents.shop.CartStoreData
import com.vayunmathur.library.intents.shop.GetProductQuery
import com.vayunmathur.library.intents.shop.ProductData
import com.vayunmathur.library.intents.shop.ProductDetailData
import com.vayunmathur.library.intents.shop.ProductVariantData
import com.vayunmathur.library.intents.shop.RemoveFromCartRequest
import com.vayunmathur.library.intents.shop.SearchProductsQuery
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Wire-compat tests for the fooddelivery / shop / communicate DTOs added
 * with the assistant ordering and messaging tools. These types cross apps
 * as JSON over the AssistantIntent binder, so the contracts that matter
 * are: a fully populated value round-trips unchanged, an empty JSON
 * object decodes to the documented defaults (older callers omitting
 * every field), and copy/equality behave as data-class callers expect.
 */
class NewIpcDtoRoundTripTest {

    private val json = Json

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T) {
        val encoded = json.encodeToString(serializer, value)
        assertEquals(value, json.decodeFromString(serializer, encoded))
        assertEquals(value.hashCode(), json.decodeFromString(serializer, encoded).hashCode())
    }

    private fun <T> defaults(serializer: KSerializer<T>, expected: T) {
        assertEquals(expected, json.decodeFromString(serializer, "{}"))
    }

    @Test fun foodDeliveryQueriesRoundTrip() {
        roundTrip(FindRestaurants.serializer(), FindRestaurants("pizza", 37.1, -122.2, 3))
        defaults(FindRestaurants.serializer(), FindRestaurants())
        roundTrip(GetMenu.serializer(), GetMenu(42))
        defaults(GetMenu.serializer(), GetMenu())
        roundTrip(GetCart.serializer(), GetCart(true))
        roundTrip(GetOrders.serializer(), GetOrders())
        roundTrip(
            PrepareOrder.serializer(),
            PrepareOrder(7, isPickup = true, addressId = "home", tipCents = 250, promo = "SAVE"),
        )
        defaults(PrepareOrder.serializer(), PrepareOrder())
    }

    @Test fun foodDeliveryMenuRoundTrips() {
        val modifier = MenuModifierData(1, "Extra cheese", 150)
        val group = MenuModifierGroupData(2, "Toppings", required = true, 1, 3, listOf(modifier))
        val item = MenuItemData(3, "Burger", 1299, available = true, "Mains", listOf(group))
        roundTrip(MenuModifierData.serializer(), modifier)
        roundTrip(MenuModifierGroupData.serializer(), group)
        roundTrip(MenuItemData.serializer(), item)
        roundTrip(MenuCategoryData.serializer(), MenuCategoryData(4, "Mains", listOf(3, 5)))
        roundTrip(
            MenuData.serializer(),
            MenuData(7, "Joe's", listOf(MenuCategoryData(4, "Mains", listOf(3))), listOf(item)),
        )
        roundTrip(MenuData.serializer(), MenuData(error = "MERCHANT_NOT_FOUND"))
        defaults(MenuData.serializer(), MenuData())
        defaults(RestaurantData.serializer(), RestaurantData())
    }

    @Test fun foodDeliveryCartAndOrdersRoundTrip() {
        val line = CartItemData(3, "Burger", 2, listOf(1), 2898)
        roundTrip(CartItemData.serializer(), line)
        roundTrip(SetCartItem.serializer(), SetCartItem(3, 2, listOf(1)))
        roundTrip(SetCart.serializer(), SetCart(7, listOf(SetCartItem(3, 1))))
        roundTrip(SetCart.serializer(), SetCart(7, emptyList())) // empty clears the cart
        roundTrip(CartData.serializer(), CartData(7, "Joe's", listOf(line), 2898))
        roundTrip(CartData.serializer(), CartData(error = "ITEM_UNAVAILABLE"))
        val summary = OrderSummaryData(
            merchantId = 7, merchantName = "Joe's", items = listOf(line),
            subtotalCents = 2898, tipCents = 250, totalCents = 3148,
        )
        roundTrip(OrderSummaryData.serializer(), summary)
        assertEquals("fooddelivery://checkout", OrderSummaryData().checkoutDeepLink)
        roundTrip(
            OrderData.serializer(),
            OrderData(9, "uuid-1", 7, "Joe's", "delivered", 3148, 2, "2026-11-16", isDelivery = true, isDone = true),
        )
        roundTrip(
            RestaurantData.serializer(),
            RestaurantData(7, "Joe's", "1 Main St", isOpen = true, isDeliveryEnabled = true, rating = 4.5, totalRatings = 120, distance = 1.2, tags = listOf("pizza")),
        )
        // copy keeps identity semantics callers rely on for cart edits
        assertNotEquals(line, line.copy(qty = 1))
        assertEquals(line, line.copy())
    }

    @Test fun shopDtosRoundTrip() {
        roundTrip(SearchProductsQuery.serializer(), SearchProductsQuery("shoes", 5))
        defaults(SearchProductsQuery.serializer(), SearchProductsQuery())
        val variant = ProductVariantData("v1", "Red / 42", 4999, "USD", available = true, sku = "SKU-1")
        roundTrip(ProductVariantData.serializer(), variant)
        roundTrip(
            ProductData.serializer(),
            ProductData("Shoe", "p1", "v1", "shop.example", "Store", 4999, "USD", "https://img", checkoutEligible = true),
        )
        roundTrip(GetProductQuery.serializer(), GetProductQuery("shop.example", "p1", "v1"))
        roundTrip(GetProductQuery.serializer(), GetProductQuery("shop.example", "p1"))
        roundTrip(
            ProductDetailData.serializer(),
            ProductDetailData("Shoe", "p1", "shop.example", "Store", "Desc", 4999, "USD", "", true, listOf(variant)),
        )
        roundTrip(ProductDetailData.serializer(), ProductDetailData(error = "BACKEND_FAILED"))
        val line = CartLineData("Shoe", "Red / 42", "v1", "shop.example", "Store", 4999, "USD", 2)
        roundTrip(CartLineData.serializer(), line)
        roundTrip(CartStoreData.serializer(), CartStoreData("shop.example", "Store", listOf(line)))
        roundTrip(
            com.vayunmathur.library.intents.shop.CartData.serializer(),
            com.vayunmathur.library.intents.shop.CartData(listOf(CartStoreData("shop.example", "Store", listOf(line))), 2),
        )
        roundTrip(AddToCartRequest.serializer(), AddToCartRequest("shop.example", "v1", 2))
        roundTrip(RemoveFromCartRequest.serializer(), RemoveFromCartRequest("v1"))
        roundTrip(
            com.vayunmathur.library.intents.shop.OrderData.serializer(),
            com.vayunmathur.library.intents.shop.OrderData("o1", "c1", "shop.example", "Store", "pending", 4999, "USD", 1, 123L),
        )
        assertTrue(SearchProductsQuery().limit > 0, "default search limit must be positive")
    }

    @Test fun communicateDtosRoundTrip() {
        roundTrip(FindConversationsQuery.serializer(), FindConversationsQuery("mom", 3))
        defaults(FindConversationsQuery.serializer(), FindConversationsQuery())
        val conversation = ConversationData("Sim:42", "+1555", "Mom", "see you", "Sim", isGroup = false)
        roundTrip(ConversationData.serializer(), conversation)
        roundTrip(ConversationData.serializer(), ConversationData("WhatsApp:1@s.whatsapp.net", "1", null, "", "WhatsApp", isGroup = true))
        roundTrip(GetMessagesQuery.serializer(), GetMessagesQuery("Sim:42", 20))
        roundTrip(ConversationMessageData.serializer(), ConversationMessageData("hi", "+1555", 999L, outgoing = true, "Sim"))
        roundTrip(SendMessageRequest.serializer(), SendMessageRequest("+1555", "Sim", "hello"))
        roundTrip(SendMessageRequest.serializer(), SendMessageRequest("1@s.whatsapp.net", "WhatsApp", "hello", "1@s.whatsapp.net"))
        roundTrip(SendMessageResult.serializer(), SendMessageResult(success = true))
        roundTrip(SendMessageResult.serializer(), SendMessageResult(false, "UNKNOWN_LINE"))
        assertNotEquals(conversation, conversation.copy(snippet = "other"))
    }
}
