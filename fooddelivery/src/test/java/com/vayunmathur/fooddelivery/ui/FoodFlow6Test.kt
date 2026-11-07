package com.vayunmathur.fooddelivery.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.api.BitesAuth
import com.vayunmathur.fooddelivery.api.BitesCore
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sixth pass: order-item rendering, address edit/instructions, account
 * verification-email send, and a tracking ETA in minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodFlow6Test {
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

    private fun installFake(ordersJson: String) {
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("/orders/past/all") -> SimpleResponse(200, "", ordersJson, emptyMap(), url)
                url.contains("/customers/me") -> SimpleResponse(200, "", """{"data":{"id":5,"firstName":"Ann","lastName":"Lee","email":"a@b.c","uuid":"cust-uuid"}}""", emptyMap(), url)
                url.contains("/auth/verify_phone") -> SimpleResponse(200, "", """{"state_id":"state-1"}""", emptyMap(), url)
                url.contains("/auth/token") -> SimpleResponse(200, "", """{"access_token":"acc2","refresh_token":"ref2","token_type":"Bearer","expires_in":3600}""", emptyMap(), url)
                url.contains("send-email-verification") -> SimpleResponse(200, "", "{}", emptyMap(), url)
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        AppInit.start(ctx)
    }

    @Test fun ordersRenderItemsAndModifiers() {
        installFake("""{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","createdAt":"2024-01-01T12:00:00Z","deliveredAt":"2024-01-01T00:00:00Z","merchant":{"id":1,"name":"Burger Barn","imageUrl":"https://example.com/m.png"},"orderItems":[{"id":1,"name":"Burger","quantity":2,"price":999,"modifiers":[{"name":"Cheese","price":100,"quantity":1}]},{"id":2,"name":"Fries","quantity":1,"price":399}]}]}""")
        val fetched = kotlinx.coroutines.runBlocking { com.vayunmathur.fooddelivery.api.BitesOrders.getOrders() }
        assertTrue(fetched.size == 1, "orders fake did not decode: $fetched")
        rule.setContent { OrdersScreen {} }
        awaitNode("Burger Barn")
        rule.onNodeWithText("2x Burger", substring = true).assertExists()
        rule.onNodeWithText("Cheese", substring = true).assertExists()
        rule.onNodeWithText("Fries", substring = true).assertExists()
    }

    @Test fun trackingEtaMinutesFromLiveEta() {
        val eta = java.time.Instant.now().plusSeconds(30 * 60).toString()
        installFake("""{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","pickedupAt":"2024-01-01T00:20:00Z","liveDeliveryEta":"$eta","merchant":{"id":1,"name":"Burger Barn"}}]}""")
        rule.setContent { OrderTrackingScreen(9) {} }
        awaitNode("min", substring = true)
    }

    @Test fun addressEditPrefillsForm() { runBlocking {
        AddressStore.save(ctx, SavedAddress(id = "a1", label = "Home", addressStreet = "1 Main", addressCity = "Springfield", addressState = "CA", addressZip = "12345", aptUnit = "4B", gateCode = "999", deliveryInstructions = "Leave at door", isDefault = true))
        val scope = CoroutineScope(Dispatchers.Main)
        var latest: List<SavedAddress> = AddressStore.getAll(ctx)
        var form = AddressFormState()
        rule.setContent {
            var current by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(form) }
            AccountAddressesSection(scope, ctx, true, latest, { latest = it }, current, { current = it; form = it }, { current = AddressFormState() })
        }
        awaitNode("Edit")
        rule.onNodeWithText("Edit", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        awaitNode("Edit Address")
        assertEqualsStreet(form)
    } }

    private fun assertEqualsStreet(form: AddressFormState) {
        assertTrue(form.street == "1 Main" && form.editing?.id == "a1", "edit did not prefill the form: $form")
    }

    @Test fun addressPickerInstructionsInput() {
        var instructions: String? = null
        val home = SavedAddress(id = "a1", label = "Home", addressStreet = "1 Main", aptUnit = "4B", gateCode = "999", isDefault = true)
        rule.setContent {
            CheckoutAddressPicker(listOf(home), true, home, {}, "", { instructions = it })
        }
        rule.onNodeWithText("Delivery instructions", substring = true).performTextInput("Ring bell")
        assertTrue(instructions == "Ring bell", "instructions not captured: $instructions")
    }

    @Test fun accountSendVerificationEmailShowsNotice() {
        installFake("""{"data":[]}""")
        BitesAuth.clearToken() // start signed out; the flow below signs in
        // Sign in through the account binder (phone -> code -> profile).
        rule.setContent { AccountScreen() }
        awaitNode("Sign in to Food Delivery")
        rule.onNodeWithText("Phone number").performTextInput("5551234567")
        rule.onNodeWithText("Send Code").performSemanticsAction(SemanticsActions.OnClick)
        awaitNode("Verify")
        rule.onNodeWithText("Verification code").performTextInput("123456")
        rule.onNodeWithText("Verify").performSemanticsAction(SemanticsActions.OnClick)
        awaitNode("Ann Lee")
        awaitNode("Send verification email")
        rule.onNodeWithText("Send verification email")
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitNode("Verification email sent", substring = true)
    }
}
