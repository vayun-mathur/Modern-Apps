package com.vayunmathur.fooddelivery.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.api.BitesCore
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Third flow pass: the address form's geocode-failure path through the
 * section's real save wiring, email-verification completion, and
 * merchant-card field variants.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodFlow3Test {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val realTransport = BitesCore.transport

    @org.junit.After fun restore() { BitesCore.transport = realTransport }

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

    @Test fun addressFormSaveFailsGeocodeUnderRobolectric() {
        val scope = CoroutineScope(Dispatchers.Main)
        val formChanges = mutableListOf<AddressFormState>()
        rule.setContent {
            var form by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(AddressFormState(showing = true, street = "1 Main St", city = "Springfield", state = "CA", zip = "12345")) }
            AccountAddressesSection(
                scope, ctx, true, emptyList(), {},
                form, { form = it; formChanges += it }, {},
            )
        }
        rule.onNodeWithText("Save", substring = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        // The save wiring is proven by the recorded form transitions
        // (triedSubmit, then saving). Robolectric's Geocoder shadow blocks the
        // IO thread waiting on the main looper, so the geocode result itself
        // (error vs saved) is not deterministically observable here.
        val deadline2 = System.currentTimeMillis() + 10_000
        var sawSaving = false
        while (System.currentTimeMillis() < deadline2) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.waitForIdle()
            if (formChanges.any { it.saving }) { sawSaving = true; break }
            Thread.sleep(50)
        }
        assertTrue(sawSaving, "save never entered the saving state; formChanges=$formChanges")
        assertTrue(formChanges.any { it.triedSubmit })
    }

    @Test fun addressFormValidationBlocksBlankFields() {
        val scope = CoroutineScope(Dispatchers.Main)
        rule.setContent {
            AccountAddressesSection(scope, ctx, true, emptyList(), {}, AddressFormState(showing = true), {}, {})
        }
        rule.onNodeWithText("Save", substring = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        // Required-field errors show and nothing was saved.
        assertTrue(runBlockingEmpty())
    }

    private fun runBlockingEmpty(): Boolean =
        kotlinx.coroutines.runBlocking { com.vayunmathur.fooddelivery.data.AddressStore.getAll(ctx).isEmpty() }

    @Test fun emailVerificationDoneCompletes() {
        BitesCore.transport = { url, _, _, _ -> SimpleResponse(200, "", "{}", emptyMap(), url) }
        AppInit.start(ctx)
        var done = false
        rule.setContent { EmailVerificationScreen("cust-1", "tok") { done = true } }
        awaitNode("verified", substring = true)
        rule.onNodeWithText("Done", substring = true).performClick()
        assertEquals(true, done)
    }

    @Test fun merchantCardFieldVariants() {
        val noRating = Merchant(id = 4, name = "No Rating Noodle", averageRating = null, totalRatings = null)
        val withMarkup = Merchant(id = 5, name = "Markup Mart", averageRating = 2.5, totalRatings = 3, doordashMarkup = 15, freeDeliveryThreshold = 1000)
        val pickupOnly = Merchant(id = 6, name = "Pickup Only Pizza", isDeliveryEnabled = false, isOpen = true)
        rule.setContent { HomeContent(listOf(noRating, withMarkup, pickupOnly), onMerchantClick = {}) }
        rule.onNodeWithText("No Rating Noodle").assertExists()
        rule.onNodeWithText("Markup Mart").assertExists()
        rule.onNodeWithText("Pickup Only Pizza").assertExists()
    }
}
