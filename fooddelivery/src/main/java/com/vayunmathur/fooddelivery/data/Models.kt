package com.vayunmathur.fooddelivery.data

import kotlin.math.max
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val message: String = "",
    val data: T? = null,
)

@Serializable
data class MerchantsWrapper(
    val merchants: List<Merchant> = emptyList(),
)

@Serializable
data class Merchant(
    val id: Int = 0,
    val name: String = "",
    val addressStreet: String = "",
    val addressCity: String = "",
    val addressState: String = "",
    val addressZip: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val logoUrl: String = "",
    val imageUrl: String = "",
    val isOpen: Boolean = true,
    val isDeliveryEnabled: Boolean = true,
    val isPickupEnabled: Boolean = true,
    val closingTime: String = "",
    val nextOpenWindow: String = "",
    val storefrontAlias: String = "",
    val averageRating: Double? = null,
    val totalRatings: Int? = null,
    val rewardsPercentage: Double? = null,
    val merchantTags: List<String> = emptyList(),
    val brandColor: String? = null,
    val freeDeliveryThreshold: Int = 0,
    val doordashMarkup: Int? = null,
    val doordashMarkupComparison: Int? = null,
    val items: List<MerchantItem> = emptyList(),
    val brand: Brand? = null,
    val distance: Double? = null,
    val sortOrder: Int? = null,
) {
    val address: String get() = buildString {
        if (addressStreet.isNotEmpty()) append(addressStreet)
        if (addressCity.isNotEmpty()) append(", $addressCity")
        if (addressState.isNotEmpty()) append(", $addressState")
    }
    val displayImage: String get() = imageUrl.ifEmpty { brand?.imageUrl ?: "" }
    val displayLogo: String get() = logoUrl.ifEmpty { brand?.logoUrl ?: "" }
    val freeDeliveryThresholdDollars: Double get() = freeDeliveryThreshold / 100.0
    val displayRating: Double get() = averageRating ?: 0.0
    val displayTotalRatings: Int get() = totalRatings ?: 0
    val displayRewardsPercentage: Double get() = rewardsPercentage ?: 0.0
}

@Serializable
data class Brand(
    val logoUrl: String? = null,
    val imageUrl: String? = null,
    val showOnApp: Boolean = false,
)

@Serializable
data class MerchantItem(
    val id: Int = 0,
    val name: String = "",
    val price: Int = 0,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
) {
    val priceDollars: Double get() = price / 100.0
}

@Serializable
data class MerchantDetail(
    val id: Int = 0,
    val name: String = "",
    val addressStreet: String = "",
    val addressCity: String = "",
    val addressState: String = "",
    val addressZip: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val logoUrl: String = "",
    val imageUrl: String = "",
    val isOpen: Boolean = true,
    val isDeliveryEnabled: Boolean = true,
    val isPickupEnabled: Boolean = true,
    val closingTime: String = "",
    val nextOpenWindow: String = "",
    val averageRating: Double? = null,
    val totalRatings: Int? = null,
    val rewardsPercentage: Double? = null,
    val merchantTags: List<String> = emptyList(),
    val brandColor: String? = null,
    val freeDeliveryThreshold: Int = 0,
    val doordashMarkup: Int? = null,
    val doordashMarkupComparison: Int? = null,
    val doordashUrl: String? = null,
    val categories: List<MenuCategory> = emptyList(),
    val items: List<MenuItem> = emptyList(),
    val deals: List<Deal> = emptyList(),
    val promotions: List<Promotion> = emptyList(),
)

@Serializable
data class MenuCategory(
    val id: Int = 0,
    val name: String = "",
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val itemIds: List<Int> = emptyList(),
    val merchantId: Int = 0,
)

@Serializable
data class MenuItem(
    val id: Int = 0,
    val name: String = "",
    val description: String = "",
    val price: Int = 0,
    val isAvailable: Boolean = true,
    val isInStock: Boolean = true,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val category: String = "",
    val merchantId: Int = 0,
    val doordashPrice: Int? = null,
    val ubereatsPrice: Int? = null,
    val isRecommended: Boolean = false,
    val isCatering: Boolean = false,
    val modifierGroups: List<ModifierGroup> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    val priceDollars: Double get() = price / 100.0
    val displayImage: String get() = imageUrl ?: thumbnailUrl ?: ""
    val doordashPriceDollars: Double? get() = doordashPrice?.let { it / 100.0 }
}

@Serializable
data class ModifierGroup(
    val id: Int = 0,
    val name: String = "",
    val required: Boolean = false,
    val minSelections: Int = 0,
    val maxSelections: Int = 1,
    val modifiers: List<Modifier> = emptyList(),
)

@Serializable
data class Modifier(
    val id: Int = 0,
    val name: String = "",
    val price: Int = 0,
) {
    val priceDollars: Double get() = price / 100.0
}

@Serializable
data class Deal(
    val id: Int = 0,
    val title: String = "",
    val description: String = "",
    val image: String = "",
    val merchantId: Int = 0,
    val merchantName: String = "",
    val discountPercent: Double = 0.0,
    val discountAmount: Int = 0,
    val isActive: Boolean = true,
    /** Orders needed to unlock; the progress threshold lives here, not on DealProgress. */
    val minOrderCount: Int = 0,
) {
    val discountAmountDollars: Double get() = discountAmount / 100.0
}

@Serializable
data class Promotion(
    val id: Int = 0,
    val name: String = "",
    val description: String = "",
)

@Serializable
data class OrderMerchant(
    val id: Int = 0,
    val name: String = "",
    val imageUrl: String? = null,
    val logoUrl: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val addressStreet: String? = null,
    val phone: String? = null,
)

/** Where the order is going. The tracking screen measures the driver against this. */
@Serializable
data class OrderAddress(
    val addressStreet: String = "",
    val addressCity: String = "",
    val addressState: String = "",
    val addressZip: String = "",
    val addressUnit: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** One sample from the courier's location trail; the last entry is the current position. */
@Serializable
data class DriverLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAt: String? = null,
)

/**
 * Live status of an order, computed exactly as the reference does
 * (bites-js-decompiled.js:903690-903915): driver proximity wins over pickup/prep state,
 * and "Arriving Soon" is within 0.1 miles of the destination.
 */
enum class OrderStage(val label: String) {
    DELIVERED("Delivered"),
    ARRIVING("Arriving Soon"),
    DRIVING("Driving to you"),
    PICKED_UP("Picked Up"),
    READY("Ready for Pickup"),
    PREPARING_SOON("Preparing Soon"),
    PREPARING("Preparing"),
}

/** One line of the rewards breakdown, e.g. "Deal Unlocked". Descriptive only. */
@Serializable
data class RewardDiscount(
    val name: String = "",
)

/**
 * GET /orders/{uuid}/rewards. [rewardsAvailable] is the credit applied to this order —
 * the reference subtracts exactly this from the order's component sum to get the total
 * it displays (bites-js-decompiled.js:1255996), and earns
 * `floor(rewardsRate * (foodTotal - rewardsAvailable))` points on top.
 */
@Serializable
data class OrderRewards(
    val rewardsAvailable: Int = 0,
    val rewardsRate: Double = 0.0,
    val discounts: List<RewardDiscount> = emptyList(),
) {
    val rewardsAvailableDollars: Double get() = rewardsAvailable / 100.0
}

@Serializable
data class Order(
    val id: Int = 0,
    /** Order UUID — the key for GET /orders/{uuid}/rewards. */
    val uuid: String? = null,
    val createdAt: String? = null,
    val channel: String? = null,
    val merchant: OrderMerchant? = null,
    val orderItems: List<OrderItem> = emptyList(),
    val foodTotal: Int = 0,
    val deliveryFee: Int = 0,
    val fees: Int? = null,
    val taxes: Int = 0,
    val tips: Int = 0,
    val promoCode: String? = null,
    val deliveredAt: String? = null,
    val pickedupAt: String? = null,
    val dueAt: String? = null,
    /**
     * Server-computed total. Confirmed present on /orders/past/all as `orderTotal`
     * (the earlier guess of `total` is not a field the API sends). Authoritative.
     */
    val orderTotal: Int? = null,
    // ---- Live tracking ----
    val address: OrderAddress? = null,
    val driverLocations: List<DriverLocation> = emptyList(),
    val courierName: String? = null,
    val courierPhone: String? = null,
    val courierImageUrl: String? = null,
    val pickupReadyAt: String? = null,
    /** Continuously-updated ETA once a courier is assigned, ISO-8601. */
    val liveDeliveryEta: String? = null,
    val proofOfDeliveryImageUrl: String? = null,
    val proofOfPickupImageUrl: String? = null,
    /** The delivery provider's own live-tracking page — the only driver map available. */
    val deliveryTrackingUrl: String? = null,
    val driverReachedMerchantAt: String? = null,
    val driverReachedCustomerAt: String? = null,
    val state: String? = null,
) {
    /**
     * Sum of the line components. This is NOT what gets charged when any discount
     * (rewards, deal, promo, referral bonus) applies — the reference subtracts those
     * from the same sum. Prefer [displayTotal], or the PaymentIntent amount.
     */
    val componentTotal: Double get() = (foodTotal + (fees ?: 0) + taxes + deliveryFee + tips) / 100.0

    val displayTotal: Double get() = orderTotal?.let { it / 100.0 } ?: componentTotal
    val foodTotalDollars: Double get() = foodTotal / 100.0
    val taxesDollars: Double get() = taxes / 100.0
    val tipsDollars: Double get() = tips / 100.0
    val deliveryFeeDollars: Double get() = deliveryFee / 100.0
    val isDelivery: Boolean get() = channel == "ORDER_CHANNEL_STOREFRONT_DELIVERY"
    val isDone: Boolean get() = if (isDelivery) deliveredAt != null else pickedupAt != null
    val displayStatus: String get() = stage.label

    /** The courier's latest reported position, if any. */
    val driverPosition: DriverLocation?
        get() = driverLocations.lastOrNull { it.latitude != null && it.longitude != null }

    /**
     * Straight-line miles from the courier to the destination, or null when either end
     * is unknown. Haversine with the same earth radius the reference uses (3958.8 mi).
     */
    val driverDistanceMiles: Double?
        get() {
            val d = driverPosition ?: return null
            val a = address ?: return null
            val dLat = d.latitude ?: return null
            val dLon = d.longitude ?: return null
            val aLat = a.latitude ?: return null
            val aLon = a.longitude ?: return null
            val toRad = Math.PI / 180.0
            val dPhi = (aLat - dLat) * toRad
            val dLam = (aLon - dLon) * toRad
            val h = Math.sin(dPhi / 2) * Math.sin(dPhi / 2) +
                Math.cos(dLat * toRad) * Math.cos(aLat * toRad) *
                Math.sin(dLam / 2) * Math.sin(dLam / 2)
            return 3958.8 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
        }

    /**
     * The ISO timestamps this order's stage and ETA are derived from, parsed once. Parsing is
     * the expensive part and it does not depend on the clock, so it is what gets memoised —
     * [stage] and [etaMillis] stay recomputed so a wall-clock transition still surfaces on a
     * list that isn't re-fetched. Delegated properties are not part of the serialized form.
     */
    private val pickupReadyAtMillis: Long? by lazy { parseIsoMillis(pickupReadyAt) }
    private val dueAtMillis: Long? by lazy { parseIsoMillis(dueAt) }
    private val liveDeliveryEtaMillis: Long? by lazy { parseIsoMillis(liveDeliveryEta) }

    /** Mirrors the reference's status ladder, including its ordering. */
    val stage: OrderStage
        get() {
            if (deliveredAt != null) return OrderStage.DELIVERED
            // A collected pickup is finished, not "driving to you": pickedupAt is the
            // pickup order's terminal timestamp (see isDone), and it is also the final
            // PICKUP milestone the live update expects. Only a delivery order is on
            // the road once it has been picked up from the merchant.
            if (!isDelivery && pickedupAt != null) return OrderStage.PICKED_UP
            val distance = driverDistanceMiles
            if (distance != null) {
                return if (distance <= 0.1) OrderStage.ARRIVING else OrderStage.DRIVING
            }
            // No coordinates on this endpoint, but the driverReached* timestamps still
            // narrate the courier's progress.
            if (driverReachedCustomerAt != null) return OrderStage.ARRIVING
            if (pickedupAt != null) return OrderStage.DRIVING
            if (driverReachedMerchantAt != null) return OrderStage.READY
            val now = System.currentTimeMillis()
            val ready = pickupReadyAtMillis
            if (ready != null && ready <= now) return OrderStage.READY
            val due = dueAtMillis
            // More than 30 minutes out, the reference shows "Preparing Soon".
            if (due != null && due - now > 1_800_000L) return OrderStage.PREPARING_SOON
            return OrderStage.PREPARING
        }

    /**
     * The reference's `maxDeliveryEta(liveDeliveryEta, dueAt)` helper
     * (bites-js-decompiled.js:908518): whichever of the two is present, and the LATER of
     * them when both are. Note it is a helper, not a field on the order.
     */
    val etaMillis: Long?
        get() {
            val live = liveDeliveryEtaMillis
            val due = dueAtMillis
            return when {
                live != null && due != null -> max(live, due)
                else -> live ?: due
            }
        }
}

@Serializable
data class OrderItem(
    val id: Int = 0,
    val name: String? = null,
    val quantity: Int = 1,
    val price: Int = 0,
    val modifiers: List<OrderItemModifier> = emptyList(),
    val specialInstructions: String? = null,
) {
    val priceDollars: Double get() = price / 100.0
}

@Serializable
data class OrderItemModifier(
    val name: String? = null,
    val price: Int = 0,
    val quantity: Int = 1,
    val modifierGroupName: String? = null,
)

@Serializable
data class SavedAddress(
    val id: String = "",
    val label: String = "",
    val addressStreet: String = "",
    val addressCity: String = "",
    val addressState: String = "",
    val addressZip: String = "",
    val aptUnit: String = "",
    val gateCode: String = "",
    val deliveryInstructions: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val isDefault: Boolean = false,
)

/**
 * A modifier the user picked, in exactly the shape the reference app's cart stores and
 * posts to /checkout: `{modifierGroupId, modifierId, name, price, quantity, modifiers}`.
 * The owning group's id is captured at selection time rather than reconstructed later.
 */
@Serializable
data class SelectedModifier(
    val modifierGroupId: Int = 0,
    val modifierId: Int = 0,
    val name: String = "",
    val price: Int = 0,
    val quantity: Int = 1,
    /** Nested modifier groups, when a modifier has its own sub-modifiers. */
    val modifiers: List<SelectedModifier> = emptyList(),
) {
    val priceDollars: Double get() = price / 100.0
}

@Serializable
data class CartItem(
    val menuItem: MenuItem,
    val quantity: Int = 1,
    val selectedModifiers: List<SelectedModifier> = emptyList(),
    val merchantId: Int = 0,
    val merchantName: String = "",
    val specialInstructions: String? = null,
    /**
     * Per-line identity so a lazy list can key on it: two cart lines can otherwise be
     * entirely equal, and keying on position makes a mid-list removal shift every later
     * row's identity. Only has to be stable while the cart is on screen.
     */
    val lineId: String = java.util.UUID.randomUUID().toString(),
) {
    /** Modifier prices count per unit of the modifier, then the whole line by item quantity. */
    val totalPrice: Double
        get() = (menuItem.price + selectedModifiers.sumOf { it.price * it.quantity }) * quantity / 100.0
}

@Serializable
data class Customer(
    val id: Int = 0,
    /** Server-side customer UUID; the reference sends this on checkout. */
    val uuid: String = "",
    val email: String = "",
    val phone: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val rewardPoints: Int = 0,
    val totalCustomerSavings: Int = 0,
) {
    val displayName: String get() = "$firstName $lastName".trim()
    val savingsDollars: Double get() = totalCustomerSavings / 100.0
}

@Serializable
data class AuthToken(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/** POST /orders/{uuid}/feedback — rating + optional note and extra tip (cents). */
@Serializable
data class FeedbackRequest(
    val orderId: Int = 0,
    val rating: Int = 0,
    val feedback: String? = null,
    val tips: Int = 0,
)

@Serializable
data class Feedback(
    val id: Int = 0,
    val orderId: Int = 0,
    val rating: Int = 0,
    val feedback: String? = null,
    val tips: Int = 0,
    val createdAt: String? = null,
)

/** GET /customers/getReferrals. */
/**
 * GET /customers/getReferrals. The reference filters this list by
 * `referral.referredById === customer.id` and counts the result
 * (bites-js-decompiled.js:1351703); the other fields are best-effort.
 */
@Serializable
data class Referral(
    val id: Int = 0,
    val referredById: Int? = null,
    val uuid: String? = null,
    val orderId: Int? = null,
    val code: String? = null,
    val status: String? = null,
    val createdAt: String? = null,
)

/**
 * GET /deals/{id}/progress. Verified against the reference's progress renderer
 * (bites-js-decompiled.js:1292710): it reads `progress.ordersCount` and
 * `progress.isComplete`, and takes the threshold from `deal.minOrderCount` — the
 * threshold is NOT on this object.
 */
@Serializable
data class DealProgress(
    val ordersCount: Int = 0,
    val isComplete: Boolean = false,
) {
    /** Unlocked when the server says so, or once the deal's own threshold is met. */
    fun isUnlocked(minOrderCount: Int): Boolean =
        isComplete || (minOrderCount > 0 && ordersCount >= minOrderCount)

    fun remaining(minOrderCount: Int): Int = (minOrderCount - ordersCount).coerceAtLeast(0)

    fun fraction(minOrderCount: Int): Float =
        if (minOrderCount <= 0) 0f else (ordersCount.toFloat() / minOrderCount).coerceIn(0f, 1f)
}

/**
 * GET /orders/savings/platform. Only `totalCustomerSavings` is observed in the
 * reference (bites-js-decompiled.js:1317712).
 */
@Serializable
data class PlatformSavings(
    val totalCustomerSavings: Int = 0,
) {
    val customerSavingsDollars: Double get() = totalCustomerSavings / 100.0
}

/** One expiring tranche of reward credit. */
@Serializable
data class RewardExpiryBatch(
    val amount: Int = 0,
    val date: String? = null,
)

/** The merchant summary embedded in each rewards row. */
@Serializable
data class RewardMerchant(
    val name: String = "",
    val logoUrl: String? = null,
    val addressCity: String? = null,
)

/**
 * One row of GET /customers/me/rewards. The reference calls this with the literal
 * "me" (bites-js-decompiled.js:224953) and iterates the result into a Map keyed by
 * `merchantId`, reading `balance`, `expiryBatches` and the nested `merchant`.
 */
@Serializable
data class MerchantRewards(
    val merchantId: Int = 0,
    val balance: Int = 0,
    val expiryBatches: List<RewardExpiryBatch> = emptyList(),
    val merchant: RewardMerchant? = null,
) {
    val balanceDollars: Double get() = balance / 100.0
}

@Serializable
data class Reward(
    val id: Int = 0,
    val title: String = "",
    val description: String = "",
    val pointsRequired: Int = 0,
    val merchantName: String = "",
)

/**
 * GET /orders/me/savings. The reference reads `customerSavings` and `orderCount` off
 * this (bites-js-decompiled.js:1317726 / :1317705) — NOT `totalCustomerSavings`, which
 * belongs to the platform-wide endpoint. The old field is kept as a fallback in case
 * the server sends both.
 */
@Serializable
data class CustomerSavings(
    val customerSavings: Int = 0,
    val orderCount: Int = 0,
    val totalCustomerSavings: Int = 0,
) {
    val customerSavingsDollars: Double
        get() = (if (customerSavings != 0) customerSavings else totalCustomerSavings) / 100.0
}

@Serializable
data class CheckoutCartItem(
    val itemId: Int,
    val quantity: Int,
    val specialInstructions: String? = null,
    val modifiers: List<SelectedModifier> = emptyList(),
)

@Serializable
data class CheckoutAddress(
    val addressStreet: String = "",
    val addressCity: String = "",
    val addressState: String = "",
    val addressZip: String = "",
    val addressUnit: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

/**
 * Body for POST /api/v1/merchants/{id}/checkout, matched field-for-field against the
 * reference app (payload built at bites-js-decompiled.js:1255592, then `address`/`orderType`
 * are replaced by `address`/`isPickup`/`inStore` in checkout() at :915556).
 *
 * `isPickup` is true for both pickup and in-store in the reference; `inStore` distinguishes
 * them. This app has no in-store mode, so `inStore` stays false.
 */
@Serializable
data class CheckoutRequest(
    val cartItems: List<CheckoutCartItem>,
    val address: CheckoutAddress? = null,
    val isPickup: Boolean = false,
    val inStore: Boolean = false,
    val tips: Int = 0,
    val promoCode: String? = null,
    val deliveryInstructions: String? = null,
    val gateCode: String? = null,
    val leaveAtDoor: Boolean = false,
    val isMobile: Boolean = true,
    /**
     * The uuid of the order a previous checkout call created, so the server UPDATEs that
     * draft instead of creating a new one (reference: the payload's `uuid` comes from the
     * stored order, bites-js-decompiled.js:1256096 sets it from the checkout response).
     * Absent on the first call. This is NOT the customer's uuid — sending that makes the
     * server attempt `orders.update()` on a nonexistent order and return HTTP 500.
     */
    val uuid: String? = null,
    // Customer identity — the reference always sends these alongside the order.
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    // Scheduled ordering; null/absent means ASAP, as in the reference when unset.
    val scheduledDate: String? = null,
    val scheduledTime: String? = null,
    /** Applied deal, or null when none — this app has no deal-selection flow yet. */
    val dealId: Int? = null,
    /** Only meaningful for pickup at a drive-thru-enabled merchant; otherwise omitted. */
    val isDriveThru: Boolean? = null,
)

@Serializable
data class CheckoutResponse(
    val clientSecret: String = "",
    val order: Order? = null,
    val serviceable: kotlinx.serialization.json.JsonElement? = null,
) {
    val isServiceable: Boolean get() = serviceable != null &&
        serviceable !is kotlinx.serialization.json.JsonPrimitive
}
