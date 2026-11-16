package com.vayunmathur.fooddelivery

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.api.BitesAuth
import com.vayunmathur.fooddelivery.api.BitesCore
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.intents.OrderLookupIntent
import com.vayunmathur.fooddelivery.notifications.OrderLiveUpdate
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.intents.fooddelivery.OrderLookupRequest
import com.vayunmathur.library.network.SimpleResponse
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/** Wave-3: MainActivity routing helpers, deep-link intents, and order lookup. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodWave3MainActivityTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val realTransport = BitesCore.transport

    @After fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
    }

    private fun installMerchants() {
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("/merchants/all/stores") -> SimpleResponse(
                    200, "",
                    """{"data":{"merchants":[
                      {"id":7,"name":"Joe's Cafe","latitude":37.0,"longitude":-122.0},
                      {"id":8,"name":"Joes Cafe Express","latitude":37.01,"longitude":-122.0},
                      {"id":9,"name":"Far Away Diner","latitude":38.0,"longitude":-122.0}
                    ]}}""",
                    emptyMap(), url,
                )
                url.contains("/orders/past/all") -> SimpleResponse(200, "", """{"data":[]}""", emptyMap(), url)
                url.contains("/merchants/") -> SimpleResponse(
                    200, "", """{"data":{"id":7,"name":"Joe's Cafe","items":[],"categories":[]}}""",
                    emptyMap(), url,
                )
                url.contains("/customers/me/rewards") -> SimpleResponse(200, "", """{"data":[]}""", emptyMap(), url)
                url.contains("/customers/verify-email") -> SimpleResponse(200, "", "{}", emptyMap(), url)
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        AppInit.start(ctx)
    }

    private fun idleLoops(times: Int = 8) {
        repeat(times) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
    }

    @Test fun canAddFromSameMerchantRules() {
        val burger = MenuItem(id = 1, name = "Burger", price = 100)
        fun line(merchant: Int) = CartItem(menuItem = burger, merchantId = merchant)
        assertTrue(emptyList<CartItem>().canAddFromSameMerchant(line(7)))
        assertTrue(listOf(line(0)).canAddFromSameMerchant(line(7)))
        assertTrue(listOf(line(7)).canAddFromSameMerchant(line(0)))
        assertTrue(listOf(line(7)).canAddFromSameMerchant(line(7)))
        assertFalse(listOf(line(7)).canAddFromSameMerchant(line(8)))
    }

    @Test fun pushDetailReplacesSameClassOnly() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main()))
        with(stack) { pushDetail(Route.Restaurant(1)) }
        assertEquals(2, stack.backStack.size)
        // A second restaurant replaces the first instead of stacking.
        with(stack) { pushDetail(Route.Restaurant(2)) }
        assertEquals(2, stack.backStack.size)
        assertEquals(2, (stack.last() as Route.Restaurant).id)
        // A different destination class stacks normally.
        with(stack) { pushDetail(Route.OrderTracking(9)) }
        assertEquals(3, stack.backStack.size)
        with(stack) { pushDetail(Route.OrderTracking(10)) }
        assertEquals(3, stack.backStack.size)
        assertEquals(10, (stack.last() as Route.OrderTracking).orderId)
        // Checkout is an object destination; pushing it after tracking stacks.
        with(stack) { pushDetail(Route.Checkout) }
        assertEquals(4, stack.backStack.size)
    }

    @Test fun orderLookupMatchesNearestNormalisedName() { runBlocking {
        installMerchants()
        val intent = Robolectric.buildActivity(OrderLookupIntent::class.java).create().get()
        // "JOE'S CAFE!!" normalises to "joescafe": exact match wins over the
        // farther prefix match, and the far diner is outside the radius anyway.
        val hit = intent.performCalculation(OrderLookupRequest(name = "JOE'S CAFE!!", lat = 37.0, lng = -122.0))
        assertTrue(hit.available)
        assertEquals(7, hit.merchantId)
        assertEquals("fooddelivery://restaurant/7", hit.deepLink)
        // Containment in the other direction also matches.
        val contained = intent.performCalculation(OrderLookupRequest(name = "Cafe", lat = 37.0, lng = -122.0))
        assertTrue(contained.available)
        // Unknown name and blank name are not orderable.
        val miss = intent.performCalculation(OrderLookupRequest(name = "Sushi Place", lat = 37.0, lng = -122.0))
        assertFalse(miss.available)
        assertEquals(0, miss.merchantId)
        assertEquals("", miss.deepLink)
        val blank = intent.performCalculation(OrderLookupRequest(name = "   ", lat = 37.0, lng = -122.0))
        assertFalse(blank.available)
        // A query a degree away matches nothing within 800 m.
        val far = intent.performCalculation(OrderLookupRequest(name = "Joe's Cafe", lat = 39.0, lng = -122.0))
        assertFalse(far.available)
    } }

    @Test fun orderLookupDegradesWhenCatalogFails() { runBlocking {
        BitesCore.transport = { _, _, _, _ -> throw java.io.IOException("down") }
        AppInit.start(ctx)
        val intent = Robolectric.buildActivity(OrderLookupIntent::class.java).create().get()
        val result = intent.performCalculation(OrderLookupRequest(name = "Joe's Cafe", lat = 37.0, lng = -122.0))
        assertFalse(result.available)
        assertEquals(0, result.merchantId)
    } }

    @Test fun mainActivityPlainLaunchComposesTabs() {
        installMerchants()
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java))
            .create().start().resume()
        idleLoops()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun mainActivityRestaurantDeepLinkLaunch() {
        installMerchants()
        val intent = Intent(ctx, MainActivity::class.java).apply {
            data = Uri.parse("fooddelivery://restaurant/7")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume()
        idleLoops()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun mainActivityTrackOrderLaunch() {
        installMerchants()
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(OrderLiveUpdate.EXTRA_TRACK_ORDER_ID, 9)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume()
        idleLoops()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun mainActivityEmailVerificationLaunchesAndNewIntents() {
        installMerchants()
        val intent = Intent(ctx, MainActivity::class.java).apply {
            data = Uri.parse("https://withbites.com/email-verification/cust-1/tok_ABC-123")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume()
        idleLoops()
        val activity = controller.get()
        // New intents: valid storefront form, then every malformed shape, which
        // must be ignored rather than crash or re-route.
        controller.newIntent(Intent(ctx, MainActivity::class.java).apply {
            data = Uri.parse("com.deliverycollective.storefront://email-verification/cust2/tok2")
        })
        controller.newIntent(Intent(ctx, MainActivity::class.java).apply {
            data = Uri.parse("https://withbites.com/email-verification/only-one")
        })
        controller.newIntent(Intent(ctx, MainActivity::class.java).apply {
            data = Uri.parse("https://withbites.com/email-verification/bad%20seg/tok")
        })
        controller.newIntent(Intent(ctx, MainActivity::class.java).apply {
            data = Uri.parse("fooddelivery://restaurant/0")
        })
        controller.newIntent(Intent(ctx, MainActivity::class.java).apply {
            data = Uri.parse("fooddelivery://other/7")
        })
        controller.newIntent(Intent(ctx, MainActivity::class.java).apply {
            data = Uri.parse("other://restaurant/7")
        })
        controller.newIntent(Intent(ctx, MainActivity::class.java).apply {
            putExtra(OrderLiveUpdate.EXTRA_TRACK_ORDER_ID, -5)
        })
        idleLoops()
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
