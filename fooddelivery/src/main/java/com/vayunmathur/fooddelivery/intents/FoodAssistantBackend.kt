package com.vayunmathur.fooddelivery.intents

import android.content.Context
import com.vayunmathur.fooddelivery.api.BitesMerchants
import com.vayunmathur.fooddelivery.api.BitesOrders
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.MerchantDetail
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.data.SelectedModifier
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.intents.fooddelivery.CartData
import com.vayunmathur.library.intents.fooddelivery.CartItemData
import com.vayunmathur.library.intents.fooddelivery.FindRestaurants
import com.vayunmathur.library.intents.fooddelivery.GetMenu
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

/** Typed failure codes returned in `error` fields (never thrown across the wire). */
internal const val ERROR_INVALID_MERCHANT = "INVALID_MERCHANT"
internal const val ERROR_MERCHANT_NOT_FOUND = "MERCHANT_NOT_FOUND"
internal const val ERROR_MERCHANT_MISMATCH = "MERCHANT_MISMATCH"
internal const val ERROR_ITEM_NOT_FOUND = "ITEM_NOT_FOUND"
internal const val ERROR_ITEM_UNAVAILABLE = "ITEM_UNAVAILABLE"
internal const val ERROR_UNKNOWN_MODIFIER = "UNKNOWN_MODIFIER"
internal const val ERROR_MODIFIER_REQUIRED = "MODIFIER_REQUIRED"
internal const val ERROR_MODIFIER_SELECTIONS = "MODIFIER_SELECTIONS_OUT_OF_RANGE"
internal const val ERROR_INVALID_QUANTITY = "INVALID_QUANTITY"
internal const val ERROR_INVALID_TIP = "INVALID_TIP"
internal const val ERROR_UNKNOWN_ADDRESS = "UNKNOWN_ADDRESS"
internal const val ERROR_EMPTY_CART = "EMPTY_CART"

internal const val MIN_CART_QTY = 1
internal const val MAX_CART_QTY = 99
internal const val DEFAULT_RESTAURANT_LIMIT = 5
internal const val MAX_RESTAURANT_LIMIT = 50

internal const val CHECKOUT_DEEP_LINK = "fooddelivery://checkout"

/**
 * Backend seam behind the fooddelivery assistant intents. Production delegates
 * to the Bites API objects, [CartStore] and [AddressStore]; tests install a
 * fake (or the Bites fake transport under the real backend) so provider logic
 * — filtering, cart/modifier validation, totals — is asserted without a
 * device. No method here ever calls `BitesOrders.checkout`: the assistant
 * prepares carts and prices them host-side, and the order itself is placed
 * and paid for in-app via the `fooddelivery://checkout` deep link.
 */
internal interface FoodAssistantBackend {
    /** Cold-process warm-up (trust bundle + saved token); no-op in tests. */
    suspend fun warmUp(context: Context)
    suspend fun getMerchants(lat: Double, lng: Double): List<Merchant>
    suspend fun getMerchantDetail(merchantId: Int): MerchantDetail?
    suspend fun getOrders(): List<Order>
    suspend fun getCart(context: Context): List<CartItem>
    suspend fun saveCart(context: Context, items: List<CartItem>)
    suspend fun getAddresses(context: Context): List<SavedAddress>
}

internal object RealFoodAssistantBackend : FoodAssistantBackend {
    override suspend fun warmUp(context: Context) {
        // These intents can run in a cold fooddelivery process; AppInit is
        // idempotent. An unattached test context has no applicationContext —
        // skip warm-up there (the Bites fake transport needs none).
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: return
        AppInit.start(appContext)
        AppInit.awaitReady()
    }

    override suspend fun getMerchants(lat: Double, lng: Double): List<Merchant> =
        BitesMerchants.getMerchants(lat, lng)

    override suspend fun getMerchantDetail(merchantId: Int): MerchantDetail? =
        BitesMerchants.getMerchantDetail(merchantId)

    override suspend fun getOrders(): List<Order> = BitesOrders.getOrders()

    override suspend fun getCart(context: Context): List<CartItem> = CartStore.getAll(context)

    override suspend fun saveCart(context: Context, items: List<CartItem>) {
        // One save = one SharedPreferences write of the whole cart JSON, so a
        // validated replacement lands atomically (never line-by-line).
        CartStore.save(context, items)
    }

    override suspend fun getAddresses(context: Context): List<SavedAddress> =
        AddressStore.getAll(context)
}

/** Test seam, mirroring communicate's `communicateAssistantBackendForTest`. */
internal var foodAssistantBackendForTest: FoodAssistantBackend? = null

internal fun foodAssistantBackend(): FoodAssistantBackend =
    foodAssistantBackendForTest ?: RealFoodAssistantBackend

// --- DTO mapping ---

internal fun Merchant.toRestaurantData(): RestaurantData = RestaurantData(
    id = id,
    name = name,
    address = address,
    isOpen = isOpen,
    isDeliveryEnabled = isDeliveryEnabled,
    isPickupEnabled = isPickupEnabled,
    rating = averageRating,
    totalRatings = totalRatings,
    distance = distance,
    tags = merchantTags,
    imageUrl = displayImage,
)

internal fun MerchantDetail.toMenuData(): MenuData = MenuData(
    merchantId = id,
    merchantName = name,
    categories = categories.map { MenuCategoryData(it.id, it.name, it.itemIds) },
    items = items.map { item ->
        MenuItemData(
            id = item.id,
            name = item.name,
            priceCents = item.price,
            available = item.isAvailable && item.isInStock,
            category = item.category,
            modifierGroups = item.modifierGroups.map { group ->
                MenuModifierGroupData(
                    id = group.id,
                    name = group.name,
                    required = group.required,
                    minSelections = group.minSelections,
                    maxSelections = group.maxSelections,
                    modifiers = group.modifiers.map {
                        MenuModifierData(it.id, it.name, it.price)
                    },
                )
            },
        )
    },
)

/** Line total in cents: (item + modifier unit prices) × quantity. */
internal fun CartItem.lineTotalCents(): Int =
    (menuItem.price + selectedModifiers.sumOf { it.price * it.quantity }) * quantity

internal fun CartItem.toCartItemData(): CartItemData = CartItemData(
    itemId = menuItem.id,
    name = menuItem.name,
    qty = quantity,
    modifierIds = selectedModifiers.map { it.modifierId },
    lineTotalCents = lineTotalCents(),
)

internal fun List<CartItem>.toCartData(error: String? = null): CartData = CartData(
    merchantId = firstOrNull()?.merchantId ?: 0,
    merchantName = firstOrNull()?.merchantName ?: "",
    items = map { it.toCartItemData() },
    totalCents = sumOf { it.lineTotalCents() },
    error = error,
)

internal fun Order.toOrderData(): OrderData = OrderData(
    id = id,
    uuid = uuid,
    merchantId = merchant?.id ?: 0,
    merchantName = merchant?.name ?: "",
    status = displayStatus,
    // orderTotal is the server-authoritative total when present; otherwise
    // fall back to the component sum, matching Order.displayTotal.
    totalCents = orderTotal ?: (foodTotal + (fees ?: 0) + taxes + deliveryFee + tips),
    itemCount = orderItems.sumOf { it.quantity },
    createdAt = createdAt,
    isDelivery = isDelivery,
    isDone = isDone,
)

// --- Intent logic (context is only used by the real backend's stores) ---

internal suspend fun findRestaurants(
    context: Context,
    input: FindRestaurants,
    backend: FoodAssistantBackend,
): List<RestaurantData> {
    backend.warmUp(context)
    val merchants = runCatching { backend.getMerchants(input.lat, input.lng) }
        .getOrDefault(emptyList())
    val query = input.query?.trim().orEmpty()
    val filtered = if (query.isEmpty()) {
        merchants
    } else {
        merchants.filter { merchant ->
            merchant.name.contains(query, ignoreCase = true) ||
                merchant.merchantTags.any { it.contains(query, ignoreCase = true) }
        }
    }
    val limit = if (input.limit <= 0) {
        DEFAULT_RESTAURANT_LIMIT
    } else {
        input.limit.coerceAtMost(MAX_RESTAURANT_LIMIT)
    }
    return filtered.take(limit).map { it.toRestaurantData() }
}

internal suspend fun getMenu(
    context: Context,
    input: GetMenu,
    backend: FoodAssistantBackend,
): MenuData {
    if (input.merchantId <= 0) {
        return MenuData(merchantId = input.merchantId, error = ERROR_INVALID_MERCHANT)
    }
    backend.warmUp(context)
    val detail = runCatching { backend.getMerchantDetail(input.merchantId) }.getOrNull()
        ?: return MenuData(merchantId = input.merchantId, error = ERROR_MERCHANT_NOT_FOUND)
    return detail.toMenuData()
}

internal suspend fun getCart(
    context: Context,
    backend: FoodAssistantBackend,
): CartData {
    backend.warmUp(context)
    return runCatching { backend.getCart(context) }.getOrDefault(emptyList()).toCartData()
}

/**
 * Validate [input] against the live menu for its merchant. Returns the built
 * cart lines, or null with [errorOut] set. Nothing is written here — the
 * caller writes the returned lines in a single [FoodAssistantBackend.saveCart]
 * call, so a rejected cart leaves the stored cart untouched.
 */
internal fun buildValidatedCart(
    detail: MerchantDetail,
    input: SetCart,
): Pair<List<CartItem>?, String?> {
    val itemsById = detail.items.associateBy { it.id }
    val built = mutableListOf<CartItem>()
    for (line in input.items) {
        if (line.qty !in MIN_CART_QTY..MAX_CART_QTY) return null to ERROR_INVALID_QUANTITY
        val item = itemsById[line.itemId] ?: return null to ERROR_ITEM_NOT_FOUND
        if (!item.isAvailable || !item.isInStock) return null to ERROR_ITEM_UNAVAILABLE
        val selected = mutableListOf<SelectedModifier>()
        for (group in item.modifierGroups) {
            val inGroup = group.modifiers.associateBy { it.id }
            val chosen = line.modifierIds.filter { it in inGroup }
            val effectiveMin = if (group.required) {
                maxOf(group.minSelections, 1)
            } else {
                group.minSelections
            }
            if (chosen.size < effectiveMin) {
                return null to if (group.required) {
                    ERROR_MODIFIER_REQUIRED
                } else {
                    ERROR_MODIFIER_SELECTIONS
                }
            }
            if (chosen.size > group.maxSelections) return null to ERROR_MODIFIER_SELECTIONS
            chosen.mapTo(selected) { modifierId ->
                val modifier = inGroup.getValue(modifierId)
                SelectedModifier(
                    modifierGroupId = group.id,
                    modifierId = modifier.id,
                    name = modifier.name,
                    price = modifier.price,
                )
            }
        }
        val knownModifierIds = item.modifierGroups
            .flatMap { group -> group.modifiers.map { it.id } }
            .toSet()
        if (line.modifierIds.any { it !in knownModifierIds }) {
            return null to ERROR_UNKNOWN_MODIFIER
        }
        built += CartItem(
            menuItem = item,
            quantity = line.qty,
            selectedModifiers = selected,
            merchantId = detail.id,
            merchantName = detail.name,
        )
    }
    return built to null
}

internal suspend fun setCart(
    context: Context,
    input: SetCart,
    backend: FoodAssistantBackend,
): CartData {
    backend.warmUp(context)
    val current = runCatching { backend.getCart(context) }.getOrDefault(emptyList())
    if (input.merchantId <= 0) return current.toCartData(error = ERROR_INVALID_MERCHANT)
    if (input.items.isEmpty()) {
        // Replacing with nothing clears the cart (single atomic write).
        backend.saveCart(context, emptyList())
        return CartData(merchantId = 0, error = null)
    }
    val detail = runCatching { backend.getMerchantDetail(input.merchantId) }.getOrNull()
        ?: return current.toCartData(error = ERROR_MERCHANT_NOT_FOUND)
    val (built, error) = buildValidatedCart(detail, input)
    if (error != null || built == null) {
        return current.toCartData(error = error ?: ERROR_ITEM_NOT_FOUND)
    }
    // Single-merchant rule: this one save replaces any previous cart, even
    // one from a different merchant — exactly the in-app "start a new cart".
    backend.saveCart(context, built)
    return built.toCartData()
}

internal suspend fun prepareOrder(
    context: Context,
    input: PrepareOrder,
    backend: FoodAssistantBackend,
): OrderSummaryData {
    backend.warmUp(context)
    val cart = runCatching { backend.getCart(context) }.getOrDefault(emptyList())
    val cartData = cart.toCartData()
    fun summary(error: String?) = OrderSummaryData(
        merchantId = cartData.merchantId,
        merchantName = cartData.merchantName,
        isPickup = input.isPickup,
        addressId = input.addressId,
        items = cartData.items,
        subtotalCents = cartData.totalCents,
        deliveryFeeCents = if (input.isPickup) 0 else null,
        taxesCents = null,
        tipCents = input.tipCents.coerceAtLeast(0),
        promo = input.promo?.trim()?.ifBlank { null },
        // Known parts only: subtotal + tip (+ 0 delivery fee for pickup).
        totalCents = cartData.totalCents + input.tipCents.coerceAtLeast(0),
        checkoutDeepLink = CHECKOUT_DEEP_LINK,
        error = error,
    )
    if (cart.isEmpty()) return summary(ERROR_EMPTY_CART)
    if (input.tipCents < 0) return summary(ERROR_INVALID_TIP)
    if (input.merchantId != 0 && input.merchantId != cartData.merchantId) {
        return summary(ERROR_MERCHANT_MISMATCH)
    }
    val addressId = input.addressId?.trim()?.ifBlank { null }
    if (addressId != null) {
        val addresses = runCatching { backend.getAddresses(context) }.getOrDefault(emptyList())
        if (addresses.none { it.id == addressId }) return summary(ERROR_UNKNOWN_ADDRESS)
    }
    // No order is created and no checkout call is made: there is no server
    // checkout-preview endpoint, so taxes / delivery fee stay null (unknown)
    // and totalCents sums only the known parts. See OrderSummaryData.
    return summary(error = null).copy(addressId = addressId)
}

internal suspend fun getOrders(
    context: Context,
    backend: FoodAssistantBackend,
): List<OrderData> {
    backend.warmUp(context)
    return runCatching { backend.getOrders() }.getOrDefault(emptyList())
        .map { it.toOrderData() }
}
