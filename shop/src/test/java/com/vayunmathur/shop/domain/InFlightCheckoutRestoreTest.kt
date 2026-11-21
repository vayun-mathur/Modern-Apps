package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.CheckoutPhase
import com.vayunmathur.shop.data.InFlightCheckout
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.UcpCheckout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Persist-then-death for shop checkout: the intent is durable before the write, and a restored record never re-creates. */
class InFlightCheckoutRestoreTest {

    private class FakeGateway : NativeCheckoutGateway {
        val intents = mutableListOf<String>()
        var reads = 0
        var creates = 0
        var response: Result<UcpCheckout> = Result.success(NativeCheckoutFixtures.checkout())
        var gate: CompletableDeferred<Unit>? = null
        /** Records visible to the sink at the moment the write is attempted. */
        var sinkSizeAtWrite = -1
        var sinkRef: () -> Int = { -1 }

        override suspend fun create(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, intent: String): Result<UcpCheckout> {
            creates++; intents += intent; sinkSizeAtWrite = sinkRef()
            gate?.await()
            return response
        }

        override suspend fun update(shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, intent: String): Result<UcpCheckout> {
            intents += intent
            return response
        }

        override suspend fun get(shop: String, sessionId: String): Result<UcpCheckout> {
            reads++
            return Result.success(NativeCheckoutFixtures.checkout(status = "ready_for_complete"))
        }
    }

    private val lines = NativeCheckoutFixtures.lines
    private val stored = mutableListOf<PlacedOrder>()
    private val sink = PendingCheckoutSink { stored += it }

    @Test fun intentIsPersistedBeforeCreateAndResolvedAfter() = runBlocking {
        val records = mutableListOf<InFlightCheckout>()
        val gateway = FakeGateway()
        gateway.sinkRef = { records.size }
        var n = 0
        val coordinator = NativeCheckoutCoordinator(
            gateway, now = { 1793034388000L }, newIntent = { "intent-${++n}" },
            intentSink = { records += it },
        )
        coordinator.submit("generic", "shop.example", lines, sink)
        // The record existed before the gateway write, carrying that write's intent.
        assertEquals(1, gateway.sinkSizeAtWrite)
        assertEquals("intent-1", records.first().intent)
        assertEquals(InFlightCheckout.STATUS_INFLIGHT, records.first().status)
        assertEquals(null, records.first().checkoutId)
        // And it resolved to the created session afterwards.
        assertEquals(InFlightCheckout.STATUS_PENDING, records.last().status)
        assertEquals("checkout-1", records.last().checkoutId)
        assertEquals(listOf("intent-1"), gateway.intents)
    }

    @Test fun deathDuringCreateRestoresUncertainAndNeverReplays() = runBlocking {
        val records = mutableListOf<InFlightCheckout>()
        val hanging = FakeGateway().apply { gate = CompletableDeferred() }
        var n = 0
        val first = NativeCheckoutCoordinator(
            hanging, now = { 1793034388000L }, newIntent = { "intent-${++n}" },
            intentSink = { records += it },
        )
        val write = launch(start = CoroutineStart.UNDISPATCHED) {
            first.submit("generic", "shop.example", lines, sink)
        }
        val inFlight = records.single()
        assertEquals(InFlightCheckout.STATUS_INFLIGHT, inFlight.status)
        write.cancelAndJoin() // process death mid-create

        // A new coordinator (new process) restoring that record must not write again.
        val gateway2 = FakeGateway()
        val restored = NativeCheckoutCoordinator(gateway2, now = { 1793034388000L }, newIntent = { "intent-new" })
        restored.restore(inFlight)
        assertEquals(CheckoutPhase.Uncertain, restored.snapshot.single().phase)
        restored.submit("generic", "shop.example", lines, sink)
        assertTrue(gateway2.intents.isEmpty())
        assertEquals(0, gateway2.creates)
    }

    @Test fun restoreWithSessionReconcilesViaGetNotCreate() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = NativeCheckoutCoordinator(gateway, now = { 1793034388000L }, newIntent = { "intent-new" })
        coordinator.restore(
            InFlightCheckout(
                intent = "intent-1", platformId = "generic", shopDomain = "shop.example",
                lines = lines, checkoutId = "checkout-1", status = InFlightCheckout.STATUS_PENDING,
                createdAtMs = 1793034388000L,
            ),
        )
        assertEquals(CheckoutPhase.Reviewing, coordinator.snapshot.single().phase)
        coordinator.reconcile("generic", "shop.example")
        assertEquals(1, gateway.reads)
        assertEquals(0, gateway.creates)
        assertTrue(gateway.intents.isEmpty())
        assertEquals("checkout-1", coordinator.snapshot.single().checkout?.checkoutId)
        assertEquals("ready_for_complete", coordinator.snapshot.single().checkout?.status)
    }
}
