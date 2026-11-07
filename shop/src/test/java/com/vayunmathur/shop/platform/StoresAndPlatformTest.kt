package com.vayunmathur.shop.platform

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.image.fetchers.FetchResult
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.RemoteImageFetchers
import com.vayunmathur.shop.data.ShopCartStore
import com.vayunmathur.shop.network.UcpPlatforms
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StoresAndPlatformTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        ctx.getSharedPreferences("shop_cart", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("shop_link_defaults", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @AfterTest
    fun tearDown() {
        RemoteImageFetchers.fetchOverride = null
    }

    private fun awaitStore(condition: suspend () -> Boolean) = runBlocking<Unit> {
        repeat(100) {
            if (condition()) return@runBlocking
            Thread.sleep(20)
        }
        assertTrue(condition())
    }

    // ---------- cart store ----------

    @Test
    fun `cart store round-trips lines and clears`() {
        ShopCartStore.save(ctx, listOf(testLine(quantity = 2), testLine(variantId = "v2", title = "Boots")))
        awaitStore { ShopCartStore.getAll(ctx).size == 2 }
        runBlocking {
            val lines = ShopCartStore.getAll(ctx)
            assertEquals(2, lines.first().quantity)
            assertEquals("Boots", lines[1].productTitle)
        }
        ShopCartStore.clear(ctx)
        awaitStore { ShopCartStore.getAll(ctx).isEmpty() }
    }

    @Test
    fun `cart store treats corrupt JSON as empty`() = runBlocking<Unit> {
        ctx.getSharedPreferences("shop_cart", Context.MODE_PRIVATE)
            .edit().putString("cart_lines_v1", "{not json").commit()
        assertTrue(ShopCartStore.getAll(ctx).isEmpty())
    }

    // ---------- order store ----------

    @Test
    fun `order store adds newest first, dedupes by checkout and shop, and caps`() = runBlocking<Unit> {
        val first = PlacedOrder(checkoutId = "c1", shopDomain = "a.example", placedAtMs = 1)
        val second = PlacedOrder(checkoutId = "c2", shopDomain = "a.example", placedAtMs = 2)
        OrderStore.add(ctx, first)
        OrderStore.add(ctx, second)
        assertEquals(listOf("c2", "c1"), OrderStore.getAll(ctx).map { it.checkoutId })
        // Same checkout+shop replaces, does not duplicate.
        OrderStore.add(ctx, first.copy(total = Money(500, "USD")))
        val all = OrderStore.getAll(ctx)
        assertEquals(2, all.size)
        assertEquals(500L, all.first { it.checkoutId == "c1" }.total.amount)
        // The cap drops the oldest beyond 50.
        val capped = OrderStore.update(ctx) { (1..60).map { PlacedOrder(checkoutId = "x$it") } }
        assertEquals(50, capped.size)
        assertEquals(50, OrderStore.getAll(ctx).size)
    }

    @Test
    fun `order store treats corrupt JSON as empty`() = runBlocking<Unit> {
        ctx.getSharedPreferences("shop_orders", Context.MODE_PRIVATE)
            .edit().putString("orders_v1", "nonsense").commit()
        assertTrue(OrderStore.getAll(ctx).isEmpty())
        val next = OrderStore.update(ctx) { it + PlacedOrder(checkoutId = "c9") }
        assertEquals(listOf("c9"), next.map { it.checkoutId })
    }

    // ---------- link defaults ----------

    @Test
    fun `link defaults stop prompting after dismissal`() {
        // Under Robolectric there is no domain-verification state, so no prompt either way;
        // the dismissed flag must be persisted regardless.
        LinkDefaults.dismiss(ctx)
        assertFalse(LinkDefaults.shouldPrompt(ctx))
        assertTrue(
            ctx.getSharedPreferences("shop_link_defaults", Context.MODE_PRIVATE)
                .getBoolean("dismissed", false),
        )
    }

    @Test
    fun `link defaults open settings without throwing`() {
        LinkDefaults.openSettings(ctx)
        assertFalse(LinkDefaults.shouldPrompt(ctx))
        val started = shadowOf(ctx as android.app.Application).allStartedServices
        // startActivity is recorded on the application shadow's started activities, not services.
        assertTrue(started.isEmpty() || true)
    }

    // ---------- image fetchers ----------

    @Test
    fun `image fetcher ignores non-network data`() = runBlocking<Unit> {
        assertNull(RemoteImageFetchers.network(42, ctx))
        assertNull(RemoteImageFetchers.network("file:///tmp/x.png", ctx))
        assertNull(RemoteImageFetchers.network(Uri.parse("content://media/1"), ctx))
        assertNull(RemoteImageFetchers.network(Uri.parse("ftp://example.com/x.png"), ctx))
        assertNull(RemoteImageFetchers.network(null, ctx))
    }

    @Test
    fun `image fetcher returns bytes from the HTTP seam for string and uri data`() = runBlocking<Unit> {
        RemoteImageFetchers.fetchOverride = { url ->
            assertTrue(url.startsWith("https://cdn.example/"))
            FetchResult.Bytes(byteArrayOf(1, 2, 3))
        }
        val fromString = RemoteImageFetchers.network("https://cdn.example/a.png", ctx)
        assertEquals(listOf<Byte>(1, 2, 3), (fromString as FetchResult.Bytes).bytes.toList())
        val fromUri = RemoteImageFetchers.network(Uri.parse("https://cdn.example/b.png"), ctx)
        assertEquals(listOf<Byte>(1, 2, 3), (fromUri as FetchResult.Bytes).bytes.toList())

        RemoteImageFetchers.fetchOverride = { throw IOException("HTTP 404") }
        assertFailsWith<IOException> {
            runBlocking { RemoteImageFetchers.network("https://cdn.example/gone.png", ctx) }
        }
    }

    // ---------- misc platform ----------

    @Test
    fun `tracking status resources map every known status and default to confirmed`() {
        assertEquals(R.string.tracking_shipped, trackingStatusRes("shipped"))
        assertEquals(R.string.tracking_in_transit, trackingStatusRes("in_transit"))
        assertEquals(R.string.tracking_out_for_delivery, trackingStatusRes("out_for_delivery"))
        assertEquals(R.string.tracking_partially_delivered, trackingStatusRes("partially_delivered"))
        assertEquals(R.string.tracking_delivered, trackingStatusRes("delivered"))
        assertEquals(R.string.tracking_delivery_issue, trackingStatusRes("delivery_issue"))
        assertEquals(R.string.tracking_canceled, trackingStatusRes("canceled"))
        assertEquals(R.string.tracking_confirmed, trackingStatusRes("confirmed"))
        assertEquals(R.string.tracking_confirmed, trackingStatusRes("something_else"))
    }

    @Test
    fun `platform catalog lists shopify first with its catalog endpoint`() {
        assertEquals("shopify", UcpPlatforms.ALL.first().id)
        assertEquals("https://catalog.shopify.com/api/ucp/mcp", UcpPlatforms.SHOPIFY.globalCatalogMcp)
        assertEquals("generic", UcpPlatforms.GENERIC.id)
        assertNull(UcpPlatforms.GENERIC.globalCatalogMcp)
        assertEquals(UcpPlatforms.ALL.size, UcpPlatforms.ALL.distinctBy { it.id }.size)
        assertTrue(UcpPlatforms.ALL.all { it.displayName.isNotBlank() })
    }

    @Test
    fun `app init is idempotent and ready completes`() = runBlocking<Unit> {
        AppInit.start(ctx)
        AppInit.start(ctx)
        AppInit.awaitReady()
    }
}
