package com.vayunmathur.fooddelivery.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SharedPreferences-backed cart and address stores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodStoresTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun addressStoreCrudAndDefault() = runBlocking {
        AddressStore.save(context, SavedAddress(id = "a1", label = "Home", addressStreet = "1 Main"))
        AddressStore.save(context, SavedAddress(id = "a2", label = "Work", addressStreet = "2 Oak"))
        assertEquals(listOf("a1", "a2"), AddressStore.getAll(context).map { it.id })
        // Saving the same id replaces rather than duplicates.
        AddressStore.save(context, SavedAddress(id = "a1", label = "Home2", addressStreet = "9 Elm"))
        assertEquals(2, AddressStore.getAll(context).size)
        assertEquals("Home2", AddressStore.getAll(context).first { it.id == "a1" }.label)
        AddressStore.setDefault(context, "a2")
        assertEquals("a2", AddressStore.getDefault(context)?.id)
        assertTrue(AddressStore.getAll(context).first { it.id == "a2" }.isDefault)
        AddressStore.delete(context, "a2")
        assertEquals(listOf("a1"), AddressStore.getAll(context).map { it.id })
        assertEquals("a1", AddressStore.getDefault(context)?.id)
    }

    @Test
    fun cartStoreEmptyAndCorruptYieldEmpty() = runBlocking {
        assertEquals(emptyList(), CartStore.getAll(context))
        context.getSharedPreferences("fooddelivery_cart", Context.MODE_PRIVATE)
            .edit().putString("cart_items_v2", "{not json").commit()
        assertEquals(emptyList(), CartStore.getAll(context))
    }

    @Test
    fun cartStoreReadsPersistedJson() = runBlocking {
        val json = """[{"menuItem":{"id":7,"name":"Salad","price":900},"quantity":3,"merchantId":4,"merchantName":"Greens","lineId":"line-1"}]"""
        context.getSharedPreferences("fooddelivery_cart", Context.MODE_PRIVATE)
            .edit().putString("cart_items_v2", json).commit()
        val items = CartStore.getAll(context)
        assertEquals(1, items.size)
        assertEquals("Salad", items[0].menuItem.name)
        assertEquals(3, items[0].quantity)
        assertEquals(27.0, items[0].totalPrice)
        assertEquals("line-1", items[0].lineId)
    }
}
