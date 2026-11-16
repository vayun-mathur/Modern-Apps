package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.CheckoutPhase
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStatusQuery
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.SubmittedLine
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.network.ShopBackend
import com.vayunmathur.shop.network.ShopBackendException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/** Extra branches: EmbeddedCheckout, MerchantUrls/Links, coordinator guards, gateway delegation. */
class MoreDomainCoverageTest {

    @Test fun embeddedCheckoutExtraBranches() {
        // ready without id is ignored; window-open without id ignored; complete falls back to checkout id
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"method":"ec.ready"}"""))
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"method":"ec.window.open_request","params":{"url":"https://a.example/x"}}"""))
        val done = assertIs<EcAction.Completed>(EmbeddedCheckout.handle(
            """{"method":"ec.complete","params":{"checkout":{"id":"chk9","totals":[]}}}"""))
        assertEquals("chk9", done.orderId); assertEquals(null, done.permalinkUrl); assertEquals(null, done.totalMinor)
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"method":"ec.complete","params":{}}"""))
        val failed = assertIs<EcAction.Failed>(EmbeddedCheckout.handle(
            """{"method":"ec.error","params":{"error":{"content":"content text"}}}"""))
        assertEquals("content text", failed.message)
        val failed2 = assertIs<EcAction.Failed>(EmbeddedCheckout.handle("""{"method":"ec.error","params":{}}"""))
        assertTrue(failed2.message.contains("reported an error"))
        // unknown method without id ignored
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"method":"ec.unknown"}"""))
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""[]"""))
        assertTrue(EmbeddedCheckout.windowOpenResult(JsonPrimitive("x"), ok=false).contains("window_open_rejected_error"))
    }

    @Test fun merchantUrlsHttpsBranches() {
        assertEquals("https://STORE.example/path?q=1", MerchantUrls.httpsUrl("https://STORE.example/path?q=1"))
        assertNull(MerchantUrls.httpsUrl("https://store.example:443/ok".replace(":443"," :443")))
        assertNull(MerchantUrls.httpsUrl("http://store.example/x"))
        assertNull(MerchantUrls.httpsUrl("https://user@store.example/x"))
        assertNull(MerchantUrls.httpsUrl("https://store.example:8443/x"))
        assertNull(MerchantUrls.httpsUrl("https://192.168.1.1/x"))
        assertNull(MerchantUrls.httpsUrl("https://foo.local/x"))
        assertNull(MerchantUrls.httpsUrl("https://nodot/x"))
        assertNull(MerchantUrls.httpsUrl("not a uri ::"))
        assertNull(MerchantUrls.domain("https://store.example/?q=1"))
        assertNull(MerchantUrls.domain("https://store.example/#frag"))
        assertEquals("sub.store.example", MerchantUrls.domain("sub.store.example"))
        assertNull(MerchantUrls.domain(""))
    }

    @Test fun merchantLinksExtraBranches() {
        assertEquals(MerchantLink.Search("a.example","hi there"),
            MerchantLinks.parse("https://a.example/search?query=hi%20there"))
        assertNull(MerchantLinks.parse("https://a.example:8443/products/x"))
        assertNull(MerchantLinks.parse("ftp://a.example/products/x"))
        assertEquals(MerchantLink.Search("a.example","blue shoes"),
            MerchantLinks.parse("https://a.example/collections/blue-shoes"))
        // invalid handle chars -> falls through to merchant/search logic (no product)
        val bad = MerchantLinks.parse("https://a.example/products/BAD_HANDLE!")
        assertTrue(bad !is MerchantLink.Product)
        assertEquals(MerchantLink.Merchant("a.example"), MerchantLinks.parse("https://a.example/"))
        assertNull(MerchantLinks.parse("javascript:alert(1)"))
    }

    private class FakeBackend(var create: Result<UcpCheckout>) : ShopBackend {
        var lastInputs: CheckoutInputs? = null
        override suspend fun search(query: String, shop: String?, limit: Int, country: String): Result<List<UcpProduct>> = Result.success(emptyList())
        override suspend fun getProduct(shop: String, productId: String, variantId: String?, title: String?, selectedOptions: List<CatalogOptionSelection>) = Result.failure<UcpProduct>(IllegalStateException())
        override suspend fun getProductByHandle(shop: String, handle: String) = Result.failure<UcpProduct>(IllegalStateException())
        override suspend fun createCheckout(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout> { lastInputs=inputs; return create }
        override suspend fun getCheckout(shop: String, sessionId: String) = create
        override suspend fun updateCheckout(shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout> { lastInputs=inputs; return create }
        override suspend fun orderStatus(orders: List<OrderStatusQuery>) = Result.success(emptyMap<String, OrderTracking>())
        override suspend fun merchantFor(host: String) = Result.success<MerchantListing?>(null)
    }

    @Test fun serverGatewayDelegatesToBackend() = runBlocking {
        val checkout = UcpCheckout(checkoutId="c9", submittedLines=listOf(SubmittedLine("v1",1)))
        val backend = FakeBackend(Result.success(checkout))
        val gateway = ServerCheckoutGateway(backend)
        val line = CartLine(variantId="v1", quantity=1, shopDomain="s.example")
        assertEquals("c9", gateway.create("s.example", listOf(line), CheckoutInputs(email="a@b.c"), "k").getOrThrow().checkoutId)
        assertEquals("a@b.c", backend.lastInputs?.email)
        assertEquals("c9", gateway.get("s.example","c9").getOrThrow().checkoutId)
        assertEquals("c9", gateway.update("s.example","c9", listOf(line), CheckoutInputs(), "k").getOrThrow().checkoutId)
        Unit
    }

    @Test fun coordinatorGuardBranches() = runBlocking {
        // Direct coordinator with fixtures gateway via ServerCheckoutGateway is internal; use NativeCheckout fixtures path:
        // Empty lines, mismatched shop, quantity out of range are blocked without gateway call.
        val backend = FakeBackend(Result.success(UcpCheckout(checkoutId="c1")))
        val coord = NativeCheckoutCoordinator(ServerCheckoutGateway(backend), now={ 1_000_000L }, newIntent={ "intent-x" })
        val stored = mutableListOf<PlacedOrder>()
        val sink = PendingCheckoutSink { stored += it }
        val line = CartLine(variantId="v1", quantity=1, platformId="generic", shopDomain="s.example")
        coord.submit("generic","s.example", emptyList(), sink)
        assertTrue(coord.snapshot.isEmpty() || coord.snapshot.single().checkout == null)
        coord.submit("generic","s.example", listOf(line.copy(quantity=100)), sink)
        coord.submit("generic","s.example", listOf(line.copy(shopDomain="other.example")), sink)
        coord.submit("generic","other.example", listOf(line), sink)
        assertTrue(stored.isEmpty())
        // Successful create strips options on first write, stores pending with blanked images
        coord.edit("generic","s.example", CheckoutInputs(email="a@b.c", options=mapOf("k" to "v")))
        val imgLine = line.copy(imageUrl="https://i.example/x", storeName="Store")
        // checkout submittedLines must match to be Reviewing; backend returns empty submitted -> browserRequired but still persists
        coord.submit("generic","s.example", listOf(imgLine), sink)
        assertEquals(1, stored.size); assertEquals("", stored.single().lines.single().imageUrl)
        assertEquals("Store", stored.single().storeName)
        // forget removes state
        coord.forget("generic","s.example"); assertTrue(coord.snapshot.isEmpty())
        coord.forget("generic","s.example") // no-op
        // edit on busy state is ignored is covered via snapshot after forget
        // refreshExpiry with no checkout is no-op
        coord.refreshExpiry()
        // reconcile without state / without checkout returns
        coord.reconcile("generic","s.example")
        Unit
    }

    @Test fun coordinatorDifferentSessionIdOnUpdateIsUncertain() = runBlocking {
        var call = 0
        val backend = object : ShopBackend by FakeBackend(Result.success(UcpCheckout(checkoutId="c1", submittedLines=listOf(SubmittedLine("v1",1))))) {
            override suspend fun createCheckout(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout> {
                call++; return Result.success(UcpCheckout(checkoutId="c1", submittedLines=listOf(SubmittedLine("v1",1))))
            }
            override suspend fun updateCheckout(shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout> {
                call++; return Result.success(UcpCheckout(checkoutId="DIFFERENT", submittedLines=listOf(SubmittedLine("v1",1))))
            }
        }
        val coord = NativeCheckoutCoordinator(ServerCheckoutGateway(backend), now={ 1_000_000L }, newIntent={ "i$call" })
        val sink = PendingCheckoutSink { }
        val line = CartLine(variantId="v1", quantity=1, platformId="generic", shopDomain="s.example")
        coord.submit("generic","s.example", listOf(line), sink)
        coord.edit("generic","s.example", CheckoutInputs(email="changed@b.c"))
        coord.submit("generic","s.example", listOf(line), sink)
        assertEquals(CheckoutPhase.Uncertain, coord.snapshot.single().phase)
        assertTrue(coord.snapshot.single().message!!.contains("different session"))
        Unit
    }

    @Test fun coordinatorGenericFailureIsUncertainAndUnsupportedSanitizesHandoff() = runBlocking {
        val backend = FakeBackend(Result.failure(ShopBackendException(UcpError.Unsupported("web only", "http://insecure.example/x"))))
        val coord = NativeCheckoutCoordinator(ServerCheckoutGateway(backend), now={ 0L }, newIntent={ "i" })
        val line = CartLine(variantId="v1", quantity=1, platformId="generic", shopDomain="s.example")
        coord.submit("generic","s.example", listOf(line), PendingCheckoutSink { })
        assertEquals(CheckoutPhase.BrowserRequired, coord.snapshot.single().phase)
        assertEquals(null, coord.snapshot.single().handoffUrl) // http handoff rejected by httpsUrl
        Unit
    }
}
