package com.vayunmathur.shop.data

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

/** Persist-then-death for the shop in-flight checkout intent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckoutIntentStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @BeforeTest fun clearStore() = runBlocking {
        CheckoutIntentStore.clear(context, "generic", "shop.example")
    }

    @Test fun persistThenDeathRestoresRecord() = runBlocking {
        val record = InFlightCheckout(
            intent = "intent-1", platformId = "generic", shopDomain = "shop.example",
            storeName = "Cool Store",
            lines = listOf(CartLine(variantId = "v1", shopDomain = "shop.example", quantity = 2)),
            checkoutId = null, status = InFlightCheckout.STATUS_INFLIGHT, createdAtMs = 42L,
        )
        CheckoutIntentStore.save(context, record)
        assertEquals(record, CheckoutIntentStore.get(context))
        CheckoutIntentStore.clear(context, "generic", "shop.example")
        assertNull(CheckoutIntentStore.get(context))
    }

    @Test fun clearDoesNotRemoveAnotherStoresRecord() = runBlocking {
        CheckoutIntentStore.save(
            context,
            InFlightCheckout(intent = "i", platformId = "generic", shopDomain = "other.example"),
        )
        CheckoutIntentStore.clear(context, "generic", "shop.example")
        assertEquals("other.example", CheckoutIntentStore.get(context)?.shopDomain)
    }
}
