package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.CheckoutPhase
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.data.SubmittedLine
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.network.ShopBackendException
import com.vayunmathur.shop.data.UcpError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class NativeCheckoutCoordinatorExtraTest {

    private class Gateway : NativeCheckoutGateway {
        var createResult: Result<UcpCheckout> = Result.success(NativeCheckoutFixtures.checkout())
        var updateResult: Result<UcpCheckout> = Result.success(NativeCheckoutFixtures.checkout())
        var getResult: Result<UcpCheckout> = Result.success(NativeCheckoutFixtures.checkout())
        var createCalls = 0
        var updateCalls = 0
        var getCalls = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun create(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, intent: String): Result<UcpCheckout> {
            createCalls++; gate?.await(); return createResult
        }

        override suspend fun update(shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, intent: String): Result<UcpCheckout> {
            updateCalls++; gate?.await(); return updateResult
        }

        override suspend fun get(shop: String, sessionId: String): Result<UcpCheckout> {
            getCalls++; return getResult
        }
    }

    private val platform = "generic"
    private val shop = "shop.example"
    private val lines = NativeCheckoutFixtures.lines
    private var savedOrders = mutableListOf<com.vayunmathur.shop.data.PlacedOrder>()
    private val sink = PendingCheckoutSink { savedOrders += it }

    private fun coordinator(
        gateway: Gateway,
        now: Long = 1_700_000_000_000L,
        changed: (List<NativeCheckoutState>) -> Unit = {},
    ) = NativeCheckoutCoordinator(gateway, now = { now }, newIntent = { "intent-1" }, changed = changed)

    private fun validInputs() = CheckoutInputs(
        firstName = "Ada", lastName = "Buyer", email = "ada@example.com", phone = "+14155550123",
    )

    private suspend fun NativeCheckoutCoordinator.createSession() {
        edit(platform, shop, validInputs())
        submit(platform, shop, lines, sink)
        assertEquals(CheckoutPhase.Reviewing, snapshot.single().phase)
    }

    @Test
    fun `update path reuses the session when inputs change but keep the address`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway)
        coordinator.createSession()
        assertEquals(1, gateway.createCalls)

        // Same inputs again: no second mutation.
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(0, gateway.updateCalls)

        // Changed options go through update exactly once.
        coordinator.edit(platform, shop, validInputs().copy(options = mapOf("gift" to "yes")))
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(1, gateway.updateCalls)
        assertEquals(CheckoutPhase.Reviewing, coordinator.snapshot.single().phase)
        assertEquals(2, savedOrders.size)
        Unit
    }

    @Test
    fun `removing a submitted address requires the browser`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway)
        val addressed = validInputs().copy(street = "1 Main", city = "Springfield", region = "IL", country = "US", postalCode = "62701", shippingRequested = true)
        coordinator.edit(platform, shop, addressed)
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(CheckoutPhase.Reviewing, coordinator.snapshot.single().phase)

        coordinator.edit(platform, shop, validInputs())
        coordinator.submit(platform, shop, lines, sink)
        val state = coordinator.snapshot.single()
        assertEquals(CheckoutPhase.BrowserRequired, state.phase)
        assertTrue(state.message!!.contains("shipping address"))
        assertEquals(0, gateway.updateCalls)
        Unit
    }

    @Test
    fun `an update that returns a different session is uncertain and terminal`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway)
        coordinator.createSession()
        gateway.updateResult = Result.success(NativeCheckoutFixtures.checkout().copy(checkoutId = "checkout-2"))
        coordinator.edit(platform, shop, validInputs().copy(options = mapOf("gift" to "yes")))
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(CheckoutPhase.Uncertain, coordinator.snapshot.single().phase)
        // And it cannot be replayed.
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(1, gateway.updateCalls)
        Unit
    }

    @Test
    fun `an expired previous session blocks mutation`() = runBlocking {
        val gateway = Gateway()
        // Create while fresh, then jump past the fixture's expiry.
        var now = 1_700_000_000_000L
        val coordinator = NativeCheckoutCoordinator(gateway, now = { now }, newIntent = { "i" })
        coordinator.createSession()
        now = 1_800_000_000_000L
        coordinator.edit(platform, shop, validInputs().copy(options = mapOf("gift" to "yes")))
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(CheckoutPhase.Expired, coordinator.snapshot.single().phase)
        assertEquals(0, gateway.updateCalls)
        Unit
    }

    @Test
    fun `a checkout that arrives already expired is parked as expired`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway, now = 1_800_000_000_000L)
        coordinator.edit(platform, shop, validInputs())
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(CheckoutPhase.Expired, coordinator.snapshot.single().phase)
        Unit
    }

    @Test
    fun `invalid lines and invalid inputs never reach the gateway`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway)
        coordinator.submit(platform, shop, emptyList(), sink)
        coordinator.submit(platform, shop, listOf(CartLine(variantId = "x", shopDomain = "other.example", platformId = platform, quantity = 1)), sink)
        coordinator.submit(platform, shop, listOf(CartLine(variantId = "x", shopDomain = shop, platformId = platform, quantity = 100)), sink)
        assertTrue(coordinator.snapshot.isEmpty())
        assertEquals(0, gateway.createCalls)

        coordinator.edit(platform, shop, CheckoutInputs(email = "not-an-email"))
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(CheckoutPhase.Error, coordinator.snapshot.single().phase)
        assertEquals(0, gateway.createCalls)
        Unit
    }

    @Test
    fun `a gateway that throws after the mutation started leaves the session uncertain`() = runBlocking {
        val gateway = Gateway()
        gateway.createResult = Result.failure(IllegalStateException("socket died"))
        val coordinator = coordinator(gateway)
        coordinator.edit(platform, shop, validInputs())
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(CheckoutPhase.Uncertain, coordinator.snapshot.single().phase)
        Unit
    }

    @Test
    fun `unsupported create keeps the merchant handoff`() = runBlocking {
        val gateway = Gateway()
        gateway.createResult = Result.failure(
            ShopBackendException(UcpError.Unsupported("Needs the website", "https://shop.example/checkout")),
        )
        val coordinator = coordinator(gateway)
        coordinator.edit(platform, shop, validInputs())
        coordinator.submit(platform, shop, lines, sink)
        val state = coordinator.snapshot.single()
        assertEquals(CheckoutPhase.BrowserRequired, state.phase)
        assertEquals("https://shop.example/checkout", state.handoffUrl)
        assertEquals("Needs the website", state.message)
        Unit
    }

    @Test
    fun `cart changes mark a live session browser-required but keep an uncertain one uncertain`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway)
        coordinator.createSession()
        // Unchanged cart: nothing happens.
        coordinator.cartChanged(lines)
        assertEquals(CheckoutPhase.Reviewing, coordinator.snapshot.single().phase)
        coordinator.cartChanged(listOf(lines.single().copy(quantity = 5)))
        assertEquals(CheckoutPhase.BrowserRequired, coordinator.snapshot.single().phase)

        val uncertainGateway = Gateway().apply { createResult = Result.failure(IllegalStateException("down")) }
        val uncertain = coordinator(uncertainGateway)
        uncertain.edit(platform, shop, validInputs())
        uncertain.submit(platform, shop, lines, sink)
        assertEquals(CheckoutPhase.Uncertain, uncertain.snapshot.single().phase)
        uncertain.cartChanged(listOf(lines.single().copy(quantity = 5)))
        assertEquals(CheckoutPhase.Uncertain, uncertain.snapshot.single().phase)
        Unit
    }

    @Test
    fun `refresh expiry only expires live sessions past their date`() = runBlocking {
        val gateway = Gateway()
        var now = 1_700_000_000_000L
        val coordinator = NativeCheckoutCoordinator(gateway, now = { now }, newIntent = { "i" })
        coordinator.refreshExpiry()
        coordinator.createSession()
        coordinator.refreshExpiry()
        assertEquals(CheckoutPhase.Reviewing, coordinator.snapshot.single().phase)
        now = 1_800_000_000_000L
        coordinator.refreshExpiry()
        assertEquals(CheckoutPhase.Expired, coordinator.snapshot.single().phase)
        Unit
    }

    @Test
    fun `edit is ignored while a mutation is in flight`() = runBlocking {
        val gateway = Gateway().apply { gate = CompletableDeferred() }
        val coordinator = coordinator(gateway)
        coordinator.edit(platform, shop, validInputs())
        val pending = async { coordinator.submit(platform, shop, lines, sink) }
        // Let submit reach the gateway gate.
        repeat(100) { if (gateway.createCalls != 1) kotlinx.coroutines.delay(10) }
        assertEquals(CheckoutPhase.Preparing, coordinator.snapshot.single().phase)
        coordinator.edit(platform, shop, validInputs().copy(firstName = "Grace"))
        assertEquals("Ada", coordinator.snapshot.single().inputs.firstName)
        gateway.gate?.complete(Unit)
        pending.await()
        assertEquals(CheckoutPhase.Reviewing, coordinator.snapshot.single().phase)
        Unit
    }

    @Test
    fun `forget drops the session and reports the change once`() = runBlocking {
        val gateway = Gateway()
        var publications = 0
        val coordinator = coordinator(gateway) { publications++ }
        coordinator.createSession()
        val before = publications
        coordinator.forget(platform, shop)
        assertTrue(coordinator.snapshot.isEmpty())
        assertEquals(before + 1, publications)
        coordinator.forget(platform, shop)
        assertEquals(before + 1, publications)
        Unit
    }

    // ---------- reconcile ----------

    @Test
    fun `reconcile without a session or checkout does nothing`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway)
        coordinator.reconcile(platform, shop)
        coordinator.edit(platform, shop, validInputs())
        coordinator.reconcile(platform, shop)
        assertEquals(0, gateway.getCalls)
        Unit
    }

    @Test
    fun `reconcile refreshes a matching session and flags changed lines`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway)
        coordinator.createSession()
        gateway.getResult = Result.success(
            NativeCheckoutFixtures.checkout(submitted = listOf(SubmittedLine("variant-1", 9))),
        )
        coordinator.reconcile(platform, shop)
        val state = coordinator.snapshot.single()
        assertEquals(CheckoutPhase.BrowserRequired, state.phase)
        assertTrue(state.message!!.contains("line items changed"))
        Unit
    }

    @Test
    fun `reconcile keeps uncertain sessions uncertain while refreshing them`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway)
        coordinator.createSession()
        // Drive the session uncertain via a failed update.
        gateway.updateResult = Result.failure(IllegalStateException("maybe applied"))
        coordinator.edit(platform, shop, validInputs().copy(options = mapOf("gift" to "yes")))
        coordinator.submit(platform, shop, lines, sink)
        assertEquals(CheckoutPhase.Uncertain, coordinator.snapshot.single().phase)

        gateway.getResult = Result.success(NativeCheckoutFixtures.checkout())
        coordinator.reconcile(platform, shop)
        val state = coordinator.snapshot.single()
        assertEquals(CheckoutPhase.Uncertain, state.phase)
        assertTrue(state.message!!.contains("uncertain write will not be replayed"))
        assertNotNull(state.checkout)
        Unit
    }

    @Test
    fun `reconcile ignores a different session and a failed read`() = runBlocking {
        val gateway = Gateway()
        val coordinator = coordinator(gateway)
        coordinator.createSession()
        gateway.getResult = Result.success(NativeCheckoutFixtures.checkout().copy(checkoutId = "someone-else"))
        coordinator.reconcile(platform, shop)
        assertEquals("checkout-1", coordinator.snapshot.single().checkout?.checkoutId)

        gateway.getResult = Result.failure(IllegalStateException("down"))
        coordinator.reconcile(platform, shop)
        assertEquals(CheckoutPhase.Reviewing, coordinator.snapshot.single().phase)
        Unit
    }

    @Test
    fun `state helpers reflect phase`() {
        val busy = NativeCheckoutState(platform, shop, phase = CheckoutPhase.Preparing)
        assertTrue(busy.busy)
        assertTrue(!busy.completionEnabled)
        val idle = NativeCheckoutState(platform, shop, phase = CheckoutPhase.Reviewing)
        assertTrue(!idle.busy)
        assertNull(idle.checkout)
        assertEquals(Money(0, "USD").currency, "USD")
    }
}
