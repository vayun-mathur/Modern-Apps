package com.vayunmathur.fooddelivery.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Persist-then-death for an in-flight checkout: a fresh read restores the draft a dead process wrote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckoutDraftStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest fun clearDraft() = runBlocking { CheckoutDraftStore.clear(context) }

    private fun draft(presented: Boolean = true) = CheckoutDraft(
        merchantId = 7,
        orderUuid = "order-uuid-1",
        paymentIntentId = paymentIntentId("pi_123_secret_abc"),
        clientSecret = "pi_123_secret_abc",
        isPickup = false,
        tipCents = 500,
        promoCode = "SAVE10",
        dealId = 3,
        addressId = "addr-1",
        deliveryInstructions = "Leave at door",
        paymentPresented = presented,
        savedAtMs = 42L,
    )

    @Test fun persistThenDeathRestoresDraft() = runBlocking {
        CheckoutDraftStore.save(context, draft())
        val restored = CheckoutDraftStore.get(context)
        assertEquals(draft(), restored)
        assertEquals("pi_123", restored?.paymentIntentId)
        assertEquals("order-uuid-1", restored?.orderUuid)
        CheckoutDraftStore.clear(context)
        assertNull(CheckoutDraftStore.get(context))
    }

    @Test fun corruptDraftReadsAsNone() = runBlocking {
        context.getSharedPreferences("fooddelivery_checkout", Context.MODE_PRIVATE)
            .edit().putString("checkout_draft_v1", "{not json").commit()
        assertNull(CheckoutDraftStore.get(context))
    }

    @Test fun paymentIntentIdParsing() {
        assertEquals("pi_123", paymentIntentId("pi_123_secret_abc"))
        assertNull(paymentIntentId("not-a-secret"))
        assertNull(paymentIntentId(""))
        assertNull(paymentIntentId(null))
    }

    @Test fun reconcileDecisionMatrix() {
        assertEquals(CheckoutDraftAction.None, checkoutDraftAction(null, existingOrderFound = false))
        assertEquals(CheckoutDraftAction.RestorePlaced, checkoutDraftAction(draft(), existingOrderFound = true))
        // Presented but not placed: unknown outcome, check orders — never re-create.
        assertEquals(CheckoutDraftAction.CheckOrders, checkoutDraftAction(draft(presented = true), existingOrderFound = false))
        assertEquals(CheckoutDraftAction.ResumeDraft, checkoutDraftAction(draft(presented = false), existingOrderFound = false))
    }
}
