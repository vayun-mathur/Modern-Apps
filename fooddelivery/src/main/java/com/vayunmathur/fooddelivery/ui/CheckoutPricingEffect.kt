package com.vayunmathur.fooddelivery.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.fooddelivery.BuildConfig
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CheckoutAddress
import com.vayunmathur.fooddelivery.data.CheckoutCartItem
import com.vayunmathur.fooddelivery.data.CheckoutRequest
import com.vayunmathur.fooddelivery.data.CheckoutResponse
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.network.BitesOrders
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.delay

/** Re-prices the checkout on the server whenever a pricing input changes. */
@Composable
fun CheckoutPricingEffect(
    items: List<CartItem>,
    merchantId: Int,
    canFetch: Boolean,
    orderSuccess: Boolean,
    isPickup: Boolean,
    tipCents: Int,
    selectedAddress: SavedAddress?,
    promoCode: String,
    customer: Customer?,
    selectedDealId: Int?,
    deliveryInstructions: String,
    lastOrderUuid: String?,
    onCheckoutResponseChange: (CheckoutResponse?) -> Unit,
    onErrorChange: (String?) -> Unit,
    onFetchingPricesChange: (Boolean) -> Unit,
    onLastOrderUuidChange: (String?) -> Unit,
) {
    LaunchedEffect(isPickup, tipCents, selectedAddress?.id, promoCode, customer?.uuid, selectedDealId) {
        if (!canFetch || orderSuccess) return@LaunchedEffect
        AppInit.awaitReady()
        onCheckoutResponseChange(null)
        onErrorChange(null)
        onFetchingPricesChange(true)
        delay(400)
        // Modifiers already carry their group id, price and quantity from selection time,
        // so they go over the wire exactly as the reference cart stores them.
        val cartItems = items.map { item ->
            CheckoutCartItem(
                itemId = item.menuItem.id,
                quantity = item.quantity,
                specialInstructions = item.specialInstructions,
                modifiers = item.selectedModifiers,
            )
        }
        val addr = if (!isPickup) selectedAddress?.let { a ->
            CheckoutAddress(
                addressStreet = a.addressStreet,
                addressCity = a.addressCity,
                addressState = a.addressState,
                addressZip = a.addressZip,
                addressUnit = a.aptUnit,
                latitude = a.latitude,
                longitude = a.longitude,
            )
        } else null
        val request = CheckoutRequest(
            cartItems = cartItems,
            address = addr,
            isPickup = isPickup,
            tips = tipCents,
            promoCode = promoCode.trim().ifBlank { null },
            dealId = selectedDealId,
            deliveryInstructions = deliveryInstructions.ifBlank { null },
            gateCode = selectedAddress?.gateCode?.ifBlank { null },
            uuid = lastOrderUuid,
            firstName = customer?.firstName?.ifBlank { null },
            lastName = customer?.lastName?.ifBlank { null },
            email = customer?.email?.ifBlank { null },
            phone = customer?.phone?.ifBlank { null },
        )
        // Entry reconciliation may have restored a placed order meanwhile; never
        // create a fresh draft for an order that already went through.
        if (orderSuccess) { onFetchingPricesChange(false); return@LaunchedEffect }
        val response = BitesOrders.checkout(merchantId, request)
        if (BuildConfig.DEV_BUILD) {
            Log.dev("Checkout", "response.order=${response?.order}")
            Log.dev("Checkout", "response.clientSecret=${response?.clientSecret?.take(20)}")
            Log.dev("Checkout", "response.serviceable=${response?.serviceable}")
            response?.order?.let { o ->
                Log.dev("Checkout", "order: foodTotal=${o.foodTotal} taxes=${o.taxes} deliveryFee=${o.deliveryFee} fees=${o.fees} tips=${o.tips} displayTotal=${o.displayTotal}")
            }
        }
        // Reuse the draft order on the next re-price; drop it if this call failed so we
        // don't keep asking the server to update an order it can't find.
        onLastOrderUuidChange(response?.order?.uuid)
        if (response == null) {
            onErrorChange("Failed to load pricing. Please try again.")
        } else if (!response.isServiceable) {
            onErrorChange("This address is not serviceable for delivery.")
        } else {
            onCheckoutResponseChange(response)
        }
        onFetchingPricesChange(false)
    }
}
