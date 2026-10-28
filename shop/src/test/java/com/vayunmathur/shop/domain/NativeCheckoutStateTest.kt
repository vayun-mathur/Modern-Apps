package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class NativeCheckoutStateTest {
    private class FakeGateway : NativeCheckoutGateway {
        val intents = mutableListOf<String>()
        val sentInputs = mutableListOf<CheckoutInputs>()
        var reads = 0
        var response: Result<UcpCheckout> = Result.success(NativeCheckoutFixtures.checkout())
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun create(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, intent: String): Result<UcpCheckout> {
            intents += intent; sentInputs += inputs
            gate?.await()
            return response
        }
        override suspend fun update(shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, intent: String): Result<UcpCheckout> {
            intents += intent; sentInputs += inputs
            gate?.await()
            return response
        }
        override suspend fun get(shop: String, sessionId: String): Result<UcpCheckout> { reads++; return Result.success(NativeCheckoutFixtures.checkout(status = "ready_for_complete")) }
    }
    private val lines = NativeCheckoutFixtures.lines
    private val stored = mutableListOf<PlacedOrder>()
    private val sink = PendingCheckoutSink { stored += it }
    private fun coordinator(gateway: FakeGateway, clock: () -> Long = { 1793034388000L }): NativeCheckoutCoordinator {
        var intent = 0
        return NativeCheckoutCoordinator(gateway, now = clock, newIntent = { "intent-${++intent}" })
    }

    @Test fun duplicateTapsAndUnchangedSessionReuseNeverDuplicateMutation() = runBlocking {
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val coordinator = coordinator(gateway)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.submit("generic", "shop.example", lines, sink) }
        assertEquals(CheckoutPhase.Preparing, coordinator.snapshot.single().phase)
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(listOf("intent-1"), gateway.intents)
        gateway.gate!!.complete(Unit); first.join()
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(listOf("intent-1"), gateway.intents)
        assertEquals(CheckoutPhase.Reviewing, coordinator.snapshot.single().phase)
        assertEquals("", coordinator.snapshot.single().checkout!!.continueUrl)
        assertEquals(lines, coordinator.snapshot.single().submittedLines)
        assertEquals("pending", stored.single().status)
        assertFalse(coordinator.snapshot.single().completionEnabled)
    }

    @Test fun inputEditsCreateNewIntentAndPendingHistoryContainsNoBuyerPii() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        val input = CheckoutInputs(email = "ephemeral@example.com", firstName = "PrivateFirstName", phone = "+14155550123")
        coordinator.edit("generic", "shop.example", input)
        coordinator.submit("generic", "shop.example", lines, sink)
        coordinator.edit("generic", "shop.example", input.copy(street = "PrivateStreet", city = "Test", region = "CA", country = "US", postalCode = "94043", options = mapOf("merchant-shipping/merchant-group" to "express")))
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(listOf("intent-1", "intent-2"), gateway.intents)
        assertEquals("PrivateStreet", gateway.sentInputs.last().street)
        assertEquals(gateway.sentInputs.last(), coordinator.snapshot.single().submittedInputs)
        val history = Json.encodeToString(stored)
        assertFalse(history.contains("PrivateFirstName"))
        assertFalse(history.contains("PrivateStreet"))
        assertFalse(history.contains("ephemeral@example.com"))
        assertTrue(stored.all { it.status == "pending" })
        assertEquals(lines, coordinator.snapshot.single().submittedLines)
    }

    @Test fun uncertainCreateCannotReplayEvenAfterInputChanges() = runBlocking {
        val gateway = FakeGateway().apply { response = Result.failure(IllegalStateException("delivery unknown")) }
        val coordinator = coordinator(gateway)
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(CheckoutPhase.Uncertain, coordinator.snapshot.single().phase)
        coordinator.edit("generic", "shop.example", CheckoutInputs(email = "changed@example.com"))
        coordinator.submit("generic", "shop.example", lines, sink)
        coordinator.reconcile("generic", "shop.example")
        assertEquals(listOf("intent-1"), gateway.intents)
        assertEquals(0, gateway.reads)
        assertTrue(stored.isEmpty())
        assertFalse(coordinator.snapshot.single().completionEnabled)
    }

    @Test fun uncertainUpdateAllowsOnlyGetAndNeverCompletesReadySession() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        coordinator.submit("generic", "shop.example", lines, sink)
        gateway.response = Result.failure(IllegalStateException("write timeout"))
        coordinator.edit("generic", "shop.example", CheckoutInputs(email = "edit@example.com"))
        coordinator.submit("generic", "shop.example", lines, sink)
        coordinator.reconcile("generic", "shop.example")
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(1, gateway.reads)
        assertEquals(listOf("intent-1", "intent-2"), gateway.intents)
        assertEquals(CheckoutPhase.Uncertain, coordinator.snapshot.single().phase)
        assertEquals("ready_for_complete", coordinator.snapshot.single().checkout!!.status)
        assertFalse(coordinator.snapshot.single().completionEnabled)
        assertEquals("pending", stored.single().status)
    }

    @Test fun staleCartAndExpiryDisableMutationWithoutLosingPendingLines() = runBlocking {
        val gateway = FakeGateway()
        var clock = 1793034388000L
        val coordinator = coordinator(gateway) { clock }
        coordinator.submit("generic", "shop.example", lines, sink)
        val changed = lines.map { it.copy(quantity = 3) }
        coordinator.cartChanged(changed)
        coordinator.submit("generic", "shop.example", changed, sink)
        assertEquals(CheckoutPhase.BrowserRequired, coordinator.snapshot.single().phase)
        assertEquals(lines, stored.single().lines)
        assertEquals(listOf("intent-1"), gateway.intents)
        clock = 1793145600000L
        coordinator.refreshExpiry()
        assertEquals(CheckoutPhase.Expired, coordinator.snapshot.single().phase)
        coordinator.edit("generic", "shop.example", CheckoutInputs(email = "late@example.com"))
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(listOf("intent-1"), gateway.intents)
    }

    @Test fun oneMerchantFailureDoesNotDiscardAnotherPendingSession() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        coordinator.submit("generic", "shop.example", lines, sink)
        gateway.response = Result.failure(IllegalStateException("unknown"))
        val second = lines.map { it.copy(shopDomain = "other.example") }
        coordinator.submit("generic", "other.example", second, sink)
        assertEquals(listOf(CheckoutPhase.Reviewing, CheckoutPhase.Uncertain), coordinator.snapshot.map { it.phase })
        assertEquals("shop.example", stored.single().shopDomain)
        assertEquals(lines, stored.single().lines)
        assertFalse(coordinator.snapshot.any { it.completionEnabled })
    }

    @Test fun browserOnlyRequirementsNeverUpdateAndReadyNeverCompletes() = runBlocking {
        val gateway = FakeGateway().apply { response = Result.success(NativeCheckoutFixtures.checkout(status = "ready_for_complete")) }
        val coordinator = coordinator(gateway)
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(CheckoutPhase.Reviewing, coordinator.snapshot.single().phase)
        assertFalse(coordinator.snapshot.single().completionEnabled)
        val unsupported = FakeGateway().apply { response = Result.success(NativeCheckoutFixtures.checkout(methodType = "custom")) }
        val browser = coordinator(unsupported)
        browser.submit("generic", "shop.example", lines, sink)
        browser.edit("generic", "shop.example", CheckoutInputs(email = "changed@example.com"))
        browser.submit("generic", "shop.example", lines, sink)
        assertEquals(CheckoutPhase.BrowserRequired, browser.snapshot.single().phase)
        assertEquals(1, unsupported.intents.size)
    }

    @Test fun cancelledMutationRemainsUncertainAndCannotReplay() = runBlocking {
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val coordinator = coordinator(gateway)
        val write = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.submit("generic", "shop.example", lines, sink) }
        write.cancelAndJoin()
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(CheckoutPhase.Uncertain, coordinator.snapshot.single().phase)
        assertEquals(listOf("intent-1"), gateway.intents)
        assertTrue(stored.isEmpty())
    }

    @Test fun staleMerchantLineItemsAreBrowserOnlyButPendingCartIsPreserved() = runBlocking {
        val gateway = FakeGateway().apply {
            response = Result.success(NativeCheckoutFixtures.checkout(submitted = listOf(SubmittedLine("stale-variant", 2))))
        }
        val coordinator = coordinator(gateway)
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(CheckoutPhase.BrowserRequired, coordinator.snapshot.single().phase)
        assertEquals(lines, stored.single().lines)
        assertEquals("pending", stored.single().status)
        assertFalse(coordinator.snapshot.single().completionEnabled)
        coordinator.edit("generic", "shop.example", CheckoutInputs(email = "edit@example.com"))
        coordinator.submit("generic", "shop.example", lines, sink)
        assertEquals(1, gateway.intents.size)
    }

    @Test fun invalidInputsNeverReachGateway() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        coordinator.edit("generic", "shop.example", CheckoutInputs(phone = "not-e164"))
        coordinator.submit("generic", "shop.example", lines, sink)
        assertTrue(gateway.intents.isEmpty())
        assertEquals(CheckoutPhase.Error, coordinator.snapshot.single().phase)
        assertTrue(stored.isEmpty())
    }
}
