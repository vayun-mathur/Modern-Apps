package com.vayunmathur.fooddelivery.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.api.BitesAuth
import com.vayunmathur.fooddelivery.api.BitesCore
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.network.SimpleResponse
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Seventh pass: the restaurant loyalty join (invite code -> fake API ->
 * enrolled card), driven through the real binder with a stateful fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodFlow7Test {
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

    @Test fun restaurantJoinLoyaltyFlow() {
        var joined = false
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("merchantLoyaltyCode") -> { joined = true; SimpleResponse(200, "", "{}", emptyMap(), url) }
                url.contains("/customers/me/rewards") ->
                    if (joined) SimpleResponse(200, "", """{"data":[{"merchantId":1,"balance":250}]}""", emptyMap(), url)
                    else SimpleResponse(200, "", """{"data":[]}""", emptyMap(), url)
                url.contains("/merchants/1") -> SimpleResponse(200, "", """{"data":{"id":1,"name":"Burger Barn","categories":[{"id":1,"name":"Mains","itemIds":[10]}],"items":[{"id":10,"name":"Burger","price":999}]}}""", emptyMap(), url)
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        AppInit.start(ctx)
        rule.setContent { RestaurantScreen(1, {}, {}) }
        awaitNode("Burger")
        rule.onNodeWithText("Invite code", substring = true).performTextInput("WELCOME")
        rule.onNodeWithText("Join", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(joined, "loyalty join never reached the fake API")
    }

    @Test fun restaurantLeaveLoyaltyFlow() {
        var left = false
        BitesCore.transport = { url, method, _, _ ->
            when {
                url.contains("/customers/me/rewards") -> SimpleResponse(200, "", """{"data":[{"merchantId":1,"balance":250}]}""", emptyMap(), url)
                url.contains("deleteCustomerMerchantLoyalty") -> { left = true; SimpleResponse(200, "", "{}", emptyMap(), url) }
                url.contains("/merchants/1") -> SimpleResponse(200, "", """{"data":{"id":1,"name":"Burger Barn","categories":[{"id":1,"name":"Mains","itemIds":[10]}],"items":[{"id":10,"name":"Burger","price":999}]}}""", emptyMap(), url)
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        AppInit.start(ctx)
        rule.setContent { RestaurantScreen(1, {}, {}) }
        awaitNode("Leave", substring = true)
        rule.onNodeWithText("Leave", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(left, "loyalty leave never reached the fake API")
    }
}
