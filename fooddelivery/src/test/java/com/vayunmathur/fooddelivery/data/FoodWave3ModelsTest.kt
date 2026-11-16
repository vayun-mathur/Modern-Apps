package com.vayunmathur.fooddelivery.data

import com.vayunmathur.fooddelivery.api.BitesCore
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Wave-3: model derived values, ISO parsing edges, and checkout wire shape. */
class FoodWave3ModelsTest {

    @Test fun merchantAddressAndImageFallbackBranches() {
        assertEquals("1 Main", Merchant(addressStreet = "1 Main").address)
        assertEquals(", Springfield", Merchant(addressCity = "Springfield").address)
        assertEquals("1 Main, IL", Merchant(addressStreet = "1 Main", addressState = "IL").address)
        val noBrand = Merchant(imageUrl = "", logoUrl = "")
        assertEquals("", noBrand.displayImage)
        assertEquals("", noBrand.displayLogo)
        val own = Merchant(
            imageUrl = "own-img", logoUrl = "own-logo",
            brand = Brand(logoUrl = "brand-logo", imageUrl = "brand-img"),
            averageRating = 4.5, totalRatings = 12, rewardsPercentage = 5.0,
        )
        assertEquals("own-img", own.displayImage)
        assertEquals("own-logo", own.displayLogo)
        assertEquals(4.5, own.displayRating)
        assertEquals(12, own.displayTotalRatings)
        assertEquals(5.0, own.displayRewardsPercentage)
    }

    @Test fun menuAndModifierPriceConversions() {
        assertEquals("thumb", MenuItem(imageUrl = null, thumbnailUrl = "thumb").displayImage)
        assertEquals("img", MenuItem(imageUrl = "img", thumbnailUrl = "thumb").displayImage)
        assertEquals("", MenuItem().displayImage)
        assertNull(MenuItem().doordashPriceDollars)
        assertEquals(12.5, MenuItem(doordashPrice = 1250).doordashPriceDollars)
        assertEquals(9.99, MenuItem(price = 999).priceDollars)
        assertEquals(2.5, MerchantItem(price = 250).priceDollars)
        assertEquals(1.25, Modifier(price = 125).priceDollars)
        assertEquals(3.0, Deal(discountAmount = 300).discountAmountDollars)
        assertEquals(0.75, SelectedModifier(price = 75).priceDollars)
        assertEquals(4.2, OrderItem(price = 420).priceDollars)
    }

    @Test fun dealProgressThresholdEdges() {
        val p = DealProgress(ordersCount = 3, isComplete = false)
        assertTrue(p.isUnlocked(3))
        assertTrue(p.isUnlocked(2))
        assertFalse(p.isUnlocked(4))
        assertFalse(DealProgress(ordersCount = 9).isUnlocked(0))
        assertTrue(DealProgress(ordersCount = 0, isComplete = true).isUnlocked(0))
        assertEquals(0, p.remaining(2))
        assertEquals(2, p.remaining(5))
        assertEquals(0f, p.fraction(0))
        assertEquals(0f, p.fraction(-1))
        assertEquals(1f, DealProgress(ordersCount = 9).fraction(3))
        assertEquals(0.5f, DealProgress(ordersCount = 1).fraction(2))
    }

    @Test fun customerAndSavingsDisplayValues() {
        assertEquals("Ann", Customer(firstName = "Ann").displayName)
        assertEquals("Lee", Customer(lastName = "Lee").displayName)
        assertEquals("", Customer().displayName)
        assertEquals(12.34, Customer(totalCustomerSavings = 1234).savingsDollars)
        // customerSavings wins; the legacy total is only a fallback when it is zero.
        assertEquals(5.0, CustomerSavings(customerSavings = 500, totalCustomerSavings = 999).customerSavingsDollars)
        assertEquals(9.99, CustomerSavings(customerSavings = 0, totalCustomerSavings = 999).customerSavingsDollars)
        assertEquals(0.0, CustomerSavings().customerSavingsDollars)
        assertEquals(1.5, PlatformSavings(totalCustomerSavings = 150).customerSavingsDollars)
        assertEquals(2.5, MerchantRewards(balance = 250).balanceDollars)
    }

    @Test fun orderComponentTotalsWithMissingFees() {
        val o = Order(foodTotal = 1000, deliveryFee = 200, fees = null, taxes = 80, tips = 100)
        assertEquals(13.8, o.componentTotal)
        assertEquals(13.8, o.displayTotal)
        assertEquals(10.0, o.foodTotalDollars)
        assertEquals(0.8, o.taxesDollars)
        assertEquals(1.0, o.tipsDollars)
        assertEquals(2.0, o.deliveryFeeDollars)
        assertFalse(o.isDone)
        // Pickup completion is driven by pickedupAt, delivery by deliveredAt.
        assertFalse(Order(channel = "P", deliveredAt = "2024-01-01T00:00:00Z").isDone)
        assertFalse(Order(
            channel = "ORDER_CHANNEL_STOREFRONT_DELIVERY",
            pickedupAt = "2024-01-01T00:00:00Z",
        ).isDone)
    }

    @Test fun orderStageFromDriverDistance() {
        val dest = OrderAddress(latitude = 40.0, longitude = -73.0)
        val far = Order(
            channel = "ORDER_CHANNEL_STOREFRONT_DELIVERY", address = dest,
            driverLocations = listOf(DriverLocation(latitude = 41.0, longitude = -73.0)),
        )
        val dist = far.driverDistanceMiles!!
        assertTrue(dist > 60.0, "one degree of latitude should be ~69 miles, was $dist")
        assertEquals(OrderStage.DRIVING, far.stage)
        // The last usable fix wins; trailing fixes without coordinates are skipped.
        val mixed = Order(
            channel = "ORDER_CHANNEL_STOREFRONT_DELIVERY", address = dest,
            driverLocations = listOf(
                DriverLocation(latitude = 41.0, longitude = -73.0),
                DriverLocation(latitude = 40.0, longitude = -73.0),
                DriverLocation(latitude = null, longitude = null),
            ),
        )
        assertEquals(40.0, mixed.driverPosition?.latitude)
        assertEquals(0.0, mixed.driverDistanceMiles!!, 0.001)
        assertEquals(OrderStage.ARRIVING, mixed.stage)
        // Missing either endpoint means no distance and no proximity stage.
        assertNull(Order(driverLocations = listOf(DriverLocation(1.0, 2.0))).driverDistanceMiles)
        assertNull(Order(address = OrderAddress()).driverDistanceMiles)
        assertNull(Order(
            address = OrderAddress(latitude = null, longitude = -73.0),
            driverLocations = listOf(DriverLocation(40.0, -73.0)),
        ).driverDistanceMiles)
    }

    @Test fun orderEtaCombinations() {
        val past = "2024-01-01T00:00:00Z"
        val future = "2999-01-01T00:00:00Z"
        assertEquals(parseIsoMillis(past), Order(liveDeliveryEta = past).etaMillis)
        assertEquals(parseIsoMillis(future), Order(dueAt = future).etaMillis)
        assertEquals(parseIsoMillis(future), Order(liveDeliveryEta = past, dueAt = future).etaMillis)
        assertEquals(parseIsoMillis(future), Order(liveDeliveryEta = future, dueAt = past).etaMillis)
        assertNull(Order(liveDeliveryEta = "junk", dueAt = "junk").etaMillis)
        // Delivered beats every other signal.
        assertEquals(OrderStage.DELIVERED, Order(
            deliveredAt = past, driverReachedCustomerAt = past,
            channel = "ORDER_CHANNEL_STOREFRONT_DELIVERY",
        ).stage)
        assertEquals("Arriving Soon", OrderStage.ARRIVING.label)
        assertEquals("Driving to you", OrderStage.DRIVING.label)
        assertEquals("Ready for Pickup", OrderStage.READY.label)
        assertEquals("Preparing Soon", OrderStage.PREPARING_SOON.label)
        assertEquals("Preparing", OrderStage.PREPARING.label)
    }

    @Test fun cartLineTotalsWithNestedModifierQuantities() {
        val item = MenuItem(id = 1, price = 500)
        val cart = CartItem(
            menuItem = item, quantity = 3,
            selectedModifiers = listOf(
                SelectedModifier(name = "Extra", price = 100, quantity = 2),
                SelectedModifier(name = "Free", price = 0, quantity = 5),
            ),
        )
        // (500 + 100*2 + 0*5) * 3 / 100 = 21.0
        assertEquals(21.0, cart.totalPrice)
        assertEquals(0.0, CartItem(menuItem = item, quantity = 0).totalPrice)
        assertNotEquals(cart.lineId, CartItem(menuItem = item).lineId)
        assertEquals("line-x", cart.copy(lineId = "line-x").lineId)
    }

    @Test fun checkoutResponseServiceabilityShape() {
        assertFalse(CheckoutResponse().isServiceable)
        assertFalse(CheckoutResponse(serviceable = JsonPrimitive(true)).isServiceable)
        assertFalse(CheckoutResponse(serviceable = JsonPrimitive("yes")).isServiceable)
        assertTrue(CheckoutResponse(serviceable = JsonObject(emptyMap())).isServiceable)
        assertTrue(CheckoutResponse(
            serviceable = kotlinx.serialization.json.JsonArray(emptyList()),
        ).isServiceable)
    }

    @Test fun checkoutRequestWireShapeKeepsDefaultsAndOmitsNulls() {
        val req = CheckoutRequest(
            cartItems = listOf(CheckoutCartItem(itemId = 7, quantity = 2)),
            tips = 0,
        )
        val encoded = BitesCore.json.encodeToString(CheckoutRequest.serializer(), req)
        // encodeDefaults: the reference always sends these flags, even at defaults.
        assertTrue(encoded.contains("\"isMobile\":true"), encoded)
        assertTrue(encoded.contains("\"isPickup\":false"), encoded)
        assertTrue(encoded.contains("\"tips\":0"), encoded)
        // explicitNulls=false: unset optional fields stay off the wire.
        assertFalse(encoded.contains("promoCode"), encoded)
        assertFalse(encoded.contains("scheduledDate"), encoded)
        assertFalse(encoded.contains("isDriveThru"), encoded)
        val decoded = BitesCore.json.decodeFromString<CheckoutRequest>(encoded)
        assertEquals(req, decoded)
        assertEquals(7, decoded.cartItems.single().itemId)
    }

    @Test fun serializationRoundTripsForRemainingModels() {
        val json = BitesCore.json
        val feedback = Feedback(id = 1, orderId = 9, rating = 5, feedback = "great", tips = 200, createdAt = "2024-01-01T00:00:00Z")
        assertEquals(feedback, json.decodeFromString<Feedback>(json.encodeToString(Feedback.serializer(), feedback)))
        assertNotEquals(feedback, feedback.copy(rating = 1))
        val promo = Promotion(id = 2, name = "BOGO", description = "buy one get one")
        assertEquals(promo, json.decodeFromString<Promotion>(json.encodeToString(Promotion.serializer(), promo)))
        val rewards = MerchantRewards(
            merchantId = 7, balance = 250,
            expiryBatches = listOf(RewardExpiryBatch(amount = 100, date = "2025-01-01")),
            merchant = RewardMerchant(name = "Greens", logoUrl = "logo", addressCity = "Springfield"),
        )
        val rewardsBack = json.decodeFromString<MerchantRewards>(json.encodeToString(MerchantRewards.serializer(), rewards))
        assertEquals(rewards, rewardsBack)
        assertEquals("Greens", rewardsBack.merchant?.name)
        assertEquals(100, rewardsBack.expiryBatches.single().amount)
        val referral = Referral(id = 3, referredById = 5, uuid = "u", orderId = 9, code = "ABC", status = "ACTIVE", createdAt = "2024-01-01T00:00:00Z")
        assertEquals(referral, json.decodeFromString<Referral>(json.encodeToString(Referral.serializer(), referral)))
        val reward = Reward(id = 4, title = "Free fries", description = "desc", pointsRequired = 100, merchantName = "Greens")
        assertEquals(reward, json.decodeFromString<Reward>(json.encodeToString(Reward.serializer(), reward)))
        val merchantItemOrder = OrderItem(
            id = 5, name = "Burger", quantity = 2, price = 999,
            modifiers = listOf(OrderItemModifier(name = "Cheese", price = 100, quantity = 2, modifierGroupName = "Extras")),
            specialInstructions = "no onion",
        )
        val itemBack = json.decodeFromString<OrderItem>(json.encodeToString(OrderItem.serializer(), merchantItemOrder))
        assertEquals(merchantItemOrder, itemBack)
        assertEquals("Extras", itemBack.modifiers.single().modifierGroupName)
        val om = OrderMerchant(id = 6, name = "Greens", latitude = 1.5, longitude = 2.5, phone = "555")
        assertEquals(om, json.decodeFromString<OrderMerchant>(json.encodeToString(OrderMerchant.serializer(), om)))
        val auth = AuthToken(accessToken = "a", refreshToken = "r", tokenType = "Bearer", expiresIn = 60)
        val authJson = json.encodeToString(AuthToken.serializer(), auth)
        assertTrue(authJson.contains("\"access_token\":\"a\""), authJson)
        assertEquals(auth, json.decodeFromString<AuthToken>(authJson))
        val saved = SavedAddress(id = "a1", label = "Home", isDefault = true, latitude = 3.0)
        assertEquals(saved, json.decodeFromString<SavedAddress>(json.encodeToString(SavedAddress.serializer(), saved)))
        val wrapper = MerchantsWrapper(merchants = listOf(Merchant(id = 1, name = "A")))
        assertEquals("A", json.decodeFromString<MerchantsWrapper>(
            json.encodeToString(MerchantsWrapper.serializer(), wrapper),
        ).merchants.single().name)
        val envelope = ApiResponse(message = "ok", data = 42)
        assertEquals(42, json.decodeFromString<ApiResponse<Int>>(
            json.encodeToString(ApiResponse.serializer(Int.serializer()), envelope),
        ).data)
    }

    @Test fun isoParsingCalendarAndClockEdges() {
        // Leap-day rules: 2024 and 2000 are leap years, 2023 and 1900 are not.
        assertEquals(parseIsoMillis("2024-02-29T12:00:00Z"), java.time.Instant.parse("2024-02-29T12:00:00Z").toEpochMilli())
        assertNull(parseIsoMillis("2023-02-29T12:00:00Z"))
        assertEquals(parseIsoMillis("2000-02-29T00:00:00Z"), java.time.Instant.parse("2000-02-29T00:00:00Z").toEpochMilli())
        assertNull(parseIsoMillis("1900-02-29T00:00:00Z"))
        assertNull(parseIsoMillis("2024-04-31T00:00:00Z"))
        assertNull(parseIsoMillis("2024-01-32T00:00:00Z"))
        assertNull(parseIsoMillis("2024-00-10T00:00:00Z"))
        assertNull(parseIsoMillis("2024-01-00T00:00:00Z"))
        // The java.time fallback accepts 24:00 as the end of the day.
        assertEquals(1704153600000L, parseIsoMillis("2024-01-01T24:00:00Z"))
        assertNull(parseIsoMillis("2024-01-01T00:60:00Z"))
        assertNull(parseIsoMillis("2024-01-01T00:00:60Z"))
        // Fractional seconds are truncated/padded to millis.
        assertEquals(500L, parseIsoMillis("1970-01-01T00:00:00.5Z")!! - parseIsoMillis("1970-01-01T00:00:00Z")!!)
        assertEquals(50L, parseIsoMillis("1970-01-01T00:00:00.05Z")!!)
        assertEquals(123L, parseIsoMillis("1970-01-01T00:00:00.123456Z")!!)
        // The java.time fallback tolerates a trailing fraction dot after the Z is stripped.
        assertEquals(1704067200000L, parseIsoMillis("2024-01-01T00:00:00.Z"))
        // No suffix, blank and malformed separators.
        assertEquals(0L, parseIsoMillis("1970-01-01T00:00:00"))
        assertNull(parseIsoMillis("   "))
        assertNull(parseIsoMillis("2024/01/01T00:00:00Z"))
        assertNull(parseIsoMillis("2024-01-01 00:00:00Z"))
        // Likewise, junk after the Z is stripped by the same fallback.
        assertEquals(1704067200000L, parseIsoMillis("2024-01-01T00:00:00Zjunk"))
        assertNull(parseIsoMillis("short"))
        // An explicit offset still parses through the java.time fallback.
        assertEquals(0L, parseIsoMillis("1970-01-01T01:00:00+01:00"))
    }
}
