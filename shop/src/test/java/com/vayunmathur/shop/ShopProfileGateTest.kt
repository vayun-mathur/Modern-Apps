package com.vayunmathur.shop

import com.vayunmathur.shop.ui.applyShopPendingRoute

import com.vayunmathur.shop.ui.applyShopProfileGate

import com.vayunmathur.shop.ui.shopStartRoute

import com.vayunmathur.library.util.NavBackStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Pure route-level tests for the shop profile gate (no Compose / Android). */
class ShopProfileGateTest {
    private fun stackOf(vararg routes: Route) = NavBackStack<Route>(arrayOf(*routes))

    @Test fun startRouteIsProfileSetupUntilCompleteThenSearch() {
        assertEquals(Route.ProfileSetup, shopStartRoute(profileComplete = false))
        assertEquals(Route.Search, shopStartRoute(profileComplete = true))
    }

    @Test fun profileSetupSerializesAsRoute() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            Route.ProfileSetup,
            json.decodeFromString<Route>(json.encodeToString<Route>(Route.ProfileSetup)),
        )
    }

    @Test fun gateDoesNotResetBeforeLoadOrWhileIncomplete() {
        val stack = stackOf(Route.ProfileSetup)
        assertFalse(applyShopProfileGate(stack, profileLoaded = false, profileComplete = false))
        assertFalse(applyShopProfileGate(stack, profileLoaded = true, profileComplete = false))
        assertEquals(listOf(Route.ProfileSetup), stack.backStack)
    }

    @Test fun gateResetsProfileSetupToSearchOnceComplete() {
        val stack = stackOf(Route.ProfileSetup)
        assertTrue(applyShopProfileGate(stack, profileLoaded = true, profileComplete = true))
        assertEquals(listOf(Route.Search), stack.backStack)
        // Idempotent: the lifted stack is left alone on later recompositions.
        assertFalse(applyShopProfileGate(stack, profileLoaded = true, profileComplete = true))
        assertEquals(listOf(Route.Search), stack.backStack)
    }

    @Test fun gateDoesNotResetSearchStackWhenEditingProfileViaTab() {
        val stack = stackOf(Route.Search, Route.ProductDetail("v1", "shopify", "s.example"))
        assertFalse(applyShopProfileGate(stack, profileLoaded = true, profileComplete = true))
        assertEquals(listOf(Route.Search, Route.ProductDetail("v1", "shopify", "s.example")), stack.backStack)
    }

    @Test fun pendingRouteWaitsForGateThenAppliesAfterReset() {
        val stack = stackOf(Route.ProfileSetup)
        val pending = Route.ProductDetail("v1", "shopify", "cool.example")
        // Arrived while gated: not applied, not consumed.
        assertFalse(applyShopPendingRoute(stack, pending, profileComplete = false))
        assertEquals(listOf(Route.ProfileSetup), stack.backStack)
        // Gate lifts, then the same pending route applies on top of Search.
        assertTrue(applyShopProfileGate(stack, profileLoaded = true, profileComplete = true))
        assertTrue(applyShopPendingRoute(stack, pending, profileComplete = true))
        assertEquals(listOf(Route.Search, pending), stack.backStack)
    }

    @Test fun pendingSearchRouteResetsToSearchRoot() {
        val stack = stackOf(Route.Search, Route.Cart)
        assertTrue(applyShopPendingRoute(stack, Route.Search, profileComplete = true))
        assertEquals(listOf(Route.Search), stack.backStack)
        assertFalse(applyShopPendingRoute(stack, null, profileComplete = true))
    }
}
