package com.vayunmathur.games.voxels.network

import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Persistence for outbound world ops that have been drained from the engine but not yet
 * acked by the relay — the voxels port of Office's persisted pending-ops pattern
 * (`OfficeViewModelSyncB.loadPendingOps`/`savePendingOps`, `crdtPending:<docId>`).
 *
 * Why this exists: the engine's outbound queue is drain-on-read, so once
 * [com.vayunmathur.games.voxels.util.VoxelsNative.netDrainOutbound] hands an op to Kotlin the
 * engine has forgotten it. Persisting the signed op strings *before* attempting the send,
 * and clearing them only after a successful append, means a dead socket (or a process death)
 * replays them verbatim on the next flush instead of silently dropping world edits.
 *
 * The persisted unit is the already-signed op string (`SignedOp{author,sig,ops}` JSON) —
 * exactly the string [MultiplayerPump] used to hand to `VoxelsSync.liveAppend` — so the
 * on-wire format (per-op AES-256-GCM, Base64, `AppendMsg` on `world:<id>`) is unchanged.
 * Ephemeral `PlayerTransform` presence is deliberately never persisted: it is superseded by
 * the next transform and was never part of the world log.
 */
interface PendingOpsStore {
    suspend fun load(): List<String>
    suspend fun save(ops: List<String>)
}

/** Process-local fallback (also handy in tests); production uses [DataStorePendingOpsStore]. */
class InMemoryPendingOpsStore : PendingOpsStore {
    private var ops: List<String> = emptyList()
    override suspend fun load(): List<String> = ops
    override suspend fun save(ops: List<String>) {
        this.ops = ops.toList()
    }
}

/**
 * DataStore-backed store, one key per world (`voxelsPending:<worldId>`), holding a JSON
 * list of signed-op strings in send order. Corrupt/absent data reads as empty, matching
 * Office's tolerant `loadPendingOps`.
 */
class DataStorePendingOpsStore(
    private val ds: DataStoreUtils,
    worldId: String,
) : PendingOpsStore {
    private val pendingKey = "voxelsPending:$worldId"

    override suspend fun load(): List<String> =
        ds.getStringAwait(pendingKey)
            ?.let { runCatching { JSON.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()

    override suspend fun save(ops: List<String>) {
        ds.setString(pendingKey, JSON.encodeToString(ops))
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Ordered, persisted outbound queue for signed world ops.
 *
 * [enqueueAndFlush] merges previously-unacked ops (oldest first) with newly drained ones,
 * persists the merged list *before* sending, sends the whole batch in one [send] call
 * (one `liveAppend`, as before), and clears the persisted list only when [send] reports
 * success. A failed send keeps the persisted list untouched, so the next flush — e.g. the
 * pump's next 80 ms tick after the WebSocket reconnects — replays the same ops in order.
 *
 * Exact duplicate signed strings are dropped on merge (Office does the same with
 * `signed !in pending`): a signature makes each drained op unique in practice, so this
 * only guards against re-enqueueing an op that is already awaiting an ack.
 */
class PendingOpQueue(
    private val store: PendingOpsStore,
    private val send: suspend (List<String>) -> Boolean,
) {
    private val mutex = Mutex()

    /** Number of persisted, not-yet-acked ops — what the HUD surfaces as "N unsent changes". */
    suspend fun pendingCount(): Int = mutex.withLock { store.load().size }

    /** Replays persisted ops without adding new ones (reconnect path). Returns remaining count. */
    suspend fun flushPending(): Int = enqueueAndFlush(emptyList())

    /**
     * Persists [newOps] behind any unacked ops, then tries to send the merged batch.
     * Returns the number of ops still unsent after the attempt (0 on success).
     */
    suspend fun enqueueAndFlush(newOps: List<String>): Int = mutex.withLock {
        val pending = store.load()
        val merged = pending + newOps.filter { it !in pending }
        if (merged.isEmpty()) return@withLock 0
        // Persist BEFORE send: from here on a crash/socket drop cannot lose these ops.
        if (merged != pending) store.save(merged)
        val sent = try {
            send(merged)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!sent) return@withLock merged.size
        // Acked: only now may the persisted ops be dropped.
        store.save(emptyList())
        0
    }
}
