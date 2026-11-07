package com.vayunmathur.fooddelivery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.data.OrderStage
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.data.parseIsoMillis
import com.vayunmathur.fooddelivery.ipc.OrderLookupContract
import com.vayunmathur.fooddelivery.notifications.OrderLiveUpdate
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoresAndMiscTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private fun order(jsonText: String): Order = json.decodeFromString(Order.serializer(), jsonText)

    private suspend fun <T> eventually(timeoutMs: Long = 10_000, read: suspend () -> T, matches: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = read()
        while (!matches(last) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); last = read()
        }
        assertTrue(matches(last), "condition never became true; last=$last")
        return last
    }

    @Test fun addressStoreCrudAndDefaults() { runBlocking {
        AddressStore.save(ctx, SavedAddress(id = "a1", label = "Home", addressStreet = "1 Main", isDefault = true))
        AddressStore.save(ctx, SavedAddress(id = "a2", label = "Work", addressStreet = "2 Oak"))
        assertEquals(2, AddressStore.getAll(ctx).size)
        assertEquals("a1", AddressStore.getDefault(ctx)?.id)
        // Saving a new default clears the old one.
        AddressStore.save(ctx, SavedAddress(id = "a2", label = "Work", addressStreet = "2 Oak", isDefault = true))
        assertEquals("a2", AddressStore.getDefault(ctx)?.id)
        assertEquals(false, AddressStore.getAll(ctx).first { it.id == "a1" }.isDefault)
        AddressStore.setDefault(ctx, "a1"); assertEquals("a1", AddressStore.getDefault(ctx)?.id)
        AddressStore.delete(ctx, "a1")
        assertEquals("a2", AddressStore.getAll(ctx).single().id)
        // Corrupt payload decodes as empty rather than crashing.
        ctx.getSharedPreferences("fooddelivery_addresses", Context.MODE_PRIVATE)
            .edit().putString("saved_addresses", "{oops").commit()
        assertTrue(AddressStore.getAll(ctx).isEmpty())
        assertNull(AddressStore.getDefault(ctx))
    } }

    @Test fun cartStoreRoundTripAndClear() { runBlocking {
        assertTrue(CartStore.getAll(ctx).isEmpty())
        val items = listOf(CartItem(menuItem = MenuItem(id = 10, name = "Burger", price = 999), quantity = 2))
        CartStore.save(ctx, items)
        val read = eventually(read = { CartStore.getAll(ctx) }) { it.size == 1 }
        assertEquals("Burger", read[0].menuItem.name); assertEquals(2, read[0].quantity)
        CartStore.clear(ctx)
        eventually(read = { CartStore.getAll(ctx) }) { it.isEmpty() }
    } }

    @Test fun isoTimeParsing() {
        assertEquals(1704067200000L, parseIsoMillis("2024-01-01T00:00:00Z"))
        assertEquals(1704067200500L, parseIsoMillis("2024-01-01T00:00:00.5Z"))
        assertEquals(1704067200123L, parseIsoMillis("2024-01-01T00:00:00.123456Z"))
        assertEquals(1709164800000L, parseIsoMillis("2024-02-29T00:00:00Z")) // leap day
        assertNull(parseIsoMillis("2023-02-29T00:00:00Z")) // not a leap year
        assertNull(parseIsoMillis("2024-13-01T00:00:00Z"))
        assertNull(parseIsoMillis("2024-01-32T00:00:00Z"))
        assertNull(parseIsoMillis("garbage")); assertNull(parseIsoMillis("")); assertNull(parseIsoMillis(null))
        assertNull(parseIsoMillis("2024-01-01")) // date only is not the fast shape; Instant fallback also rejects
        assertEquals(1704067200000L, parseIsoMillis("2024-01-01T00:00:00+00:00".replace("+00:00", "Z")))
        assertNotNull(parseIsoMillis("2024-01-01T00:00:00.000Z"))
    }

    @Test fun orderStageDerivation() {
        assertEquals(OrderStage.DELIVERED, order("""{"deliveredAt":"2024-01-01T00:00:00Z"}""").stage)
        assertEquals(OrderStage.PICKED_UP, order("""{"pickedupAt":"2024-01-01T00:20:00Z"}""").stage)
        assertEquals(OrderStage.DRIVING, order("""{"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","pickedupAt":"2024-01-01T00:20:00Z"}""").stage)
        assertEquals(OrderStage.ARRIVING, order("""{"driverReachedCustomerAt":"2024-01-01T00:20:00Z"}""").stage)
        assertEquals(OrderStage.READY, order("""{"driverReachedMerchantAt":"2024-01-01T00:10:00Z"}""").stage)
        assertEquals(OrderStage.READY, order("""{"pickupReadyAt":"2024-01-01T00:00:00Z"}""").stage) // ready time in the past
        assertEquals(OrderStage.PREPARING, order("{}").stage)
        assertTrue(order("{}").displayStatus.isNotBlank())
        assertNull(order("{}").etaMillis)
        val withEta = order("""{"dueAt":"2099-01-01T00:00:00Z","liveDeliveryEta":"2099-06-01T00:00:00Z"}""")
        assertEquals(parseIsoMillis("2099-06-01T00:00:00Z"), withEta.etaMillis) // later of the two wins
        assertEquals(false, order("""{"id":3}""").isDelivery) // null channel is not delivery
        assertEquals(false, order("""{"channel":"ORDER_CHANNEL_STOREFRONT_PICKUP"}""").isDelivery)
        assertEquals(true, order("""{"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY"}""").isDelivery)
    }

    @Test fun liveUpdateNotificationBuilds() {
        OrderLiveUpdate.ensureChannel(ctx)
        val delivery = OrderLiveUpdate.build(ctx, order("""{"id":9,"merchant":{"id":1,"name":"Burger Barn"}}"""))
        assertNotNull(delivery)
        val pickup = OrderLiveUpdate.build(ctx, order("""{"id":10,"channel":"ORDER_CHANNEL_STOREFRONT_PICKUP","driverReachedMerchantAt":"2024-01-01T00:10:00Z"}"""))
        assertNotNull(pickup)
        val delivered = OrderLiveUpdate.build(ctx, order("""{"id":11,"deliveredAt":"2024-01-01T00:00:00Z"}"""))
        assertNotNull(delivered)
        OrderLiveUpdate.cancel(ctx)
        assertEquals(4711, OrderLiveUpdate.NOTIFICATION_ID)
        assertEquals("fooddelivery://restaurant/42", OrderLookupContract.deepLink(42))
    }
}
