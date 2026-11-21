package com.vayunmathur.games.voxels.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Persisted pending-ops for [MultiplayerPump.flushOutbound] (port of Office's
 * `crdtPending:<docId>` pattern): ops are persisted before send, kept on failure,
 * cleared only on ack, and replayed in order. The queue under test is the pump's
 * outbound engine; recreating it over the same store is pump recreation.
 */
class PendingOpsTest {
    /** Store whose backing is a single persisted string, like the DataStore value. */
    private class PersistingStore(var backing: String? = null) : PendingOpsStore {
        override suspend fun load(): List<String> =
            backing?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()

        override suspend fun save(ops: List<String>) {
            backing = Json.encodeToString(ops)
        }
    }

    @Test fun opsPersistedBeforeSendSurvivePumpRecreation() = runBlocking {
        val store = PersistingStore()
        var atSendTime: List<String>? = null
        val firstPump = PendingOpQueue(store) { ops ->
            atSendTime = store.load() // what was persisted when the send was attempted
            false // socket down
        }
        assertEquals(2, firstPump.enqueueAndFlush(listOf("op-a", "op-b")))
        // Persisted BEFORE the (failed) send was attempted.
        assertEquals(listOf("op-a", "op-b"), atSendTime)
        assertEquals(listOf("op-a", "op-b"), store.load())

        // Recreated pump (new queue, same persisted store) replays in order, then clears.
        val replayed = mutableListOf<List<String>>()
        val secondPump = PendingOpQueue(store) { ops -> replayed += ops; true }
        assertEquals(2, secondPump.pendingCount())
        assertEquals(0, secondPump.flushPending())
        assertEquals(listOf(listOf("op-a", "op-b")), replayed)
        assertTrue(store.load().isEmpty())
    }

    @Test fun failedSendKeepsOpsAndSuccessClears() = runBlocking {
        val store = PersistingStore()
        var online = false
        val sent = mutableListOf<List<String>>()
        val queue = PendingOpQueue(store) { ops -> if (online) { sent += ops; true } else false }
        assertEquals(1, queue.enqueueAndFlush(listOf("op-1")))
        assertEquals(listOf("op-1"), store.load())
        assertTrue(sent.isEmpty())
        online = true
        assertEquals(0, queue.flushPending())
        assertEquals(listOf(listOf("op-1")), sent)
        assertTrue(store.load().isEmpty())
        assertEquals(0, queue.pendingCount())
    }

    @Test fun throwingSendKeepsOps() = runBlocking {
        val store = PersistingStore()
        val queue = PendingOpQueue(store) { throw IllegalStateException("socket closed") }
        assertEquals(1, queue.enqueueAndFlush(listOf("op-x")))
        assertEquals(listOf("op-x"), store.load())
    }

    @Test fun orderPreservedAcrossFailedBatches() = runBlocking {
        val store = PersistingStore()
        var online = false
        val sent = mutableListOf<String>()
        val queue = PendingOpQueue(store) { ops -> if (online) { sent += ops; true } else false }
        queue.enqueueAndFlush(listOf("op-1", "op-2"))
        queue.enqueueAndFlush(listOf("op-3"))
        assertEquals(listOf("op-1", "op-2", "op-3"), store.load())
        online = true
        assertEquals(0, queue.flushPending())
        assertEquals(listOf("op-1", "op-2", "op-3"), sent)
    }

    @Test fun alreadyPendingOpIsNotDuplicated() = runBlocking {
        val store = PersistingStore()
        val queue = PendingOpQueue(store) { false }
        queue.enqueueAndFlush(listOf("op-1"))
        queue.enqueueAndFlush(listOf("op-1", "op-2"))
        assertEquals(listOf("op-1", "op-2"), store.load())
    }

    @Test fun emptyFlushNeverSends() = runBlocking {
        var calls = 0
        val queue = PendingOpQueue(PersistingStore()) { calls++; true }
        assertEquals(0, queue.enqueueAndFlush(emptyList()))
        assertEquals(0, queue.flushPending())
        assertEquals(0, calls)
    }
}
