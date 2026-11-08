package com.vayunmathur.games.logicgate.data

import android.content.Context
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.Node
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.model.Wire
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Saved node. [kind] is `gate`, `in` or `out`. */
@Serializable
data class NodeDto(
    val id: Int,
    val kind: String,
    val chip: String? = null,
    val index: Int = 0,
    val x: Float,
    val y: Float,
)

/** Saved pin reference. */
@Serializable
data class PinDto(val node: Int, val out: Boolean, val index: Int)

/** Saved wire. */
@Serializable
data class WireDto(val from: PinDto, val to: PinDto)

/** Saved circuit draft. */
@Serializable
data class CircuitDto(val nodes: List<NodeDto>, val wires: List<WireDto>, val nextId: Int)

/**
 * Per-level circuit drafts, as one JSON map in the shared DataStore. Saves are debounced on the
 * store's own scope rather than the ViewModel's, so the last edit still lands after the screen
 * closes.
 *
 * Unsaved drafts wait in [latest], one slot per level: a save for one level must never cancel
 * another level's draft, [load] must see the newest state even before it is flushed, and
 * [rewriteAll] must transform unsaved drafts too, so a debounced save can never overwrite a
 * rewrite with a stale circuit.
 */
class CircuitStore(context: Context, dispatcher: CoroutineDispatcher) {
    private val ds = DataStoreUtils.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val latest = mutableMapOf<String, CircuitDto>()
    private var flush: Job? = null

    suspend fun load(levelId: String): Circuit? =
        synchronized(lock) { latest[levelId] }?.toDomain()
            ?: decode(ds.getStringAwait(KEY))[levelId]?.toDomain()

    fun save(levelId: String, circuit: Circuit) {
        synchronized(lock) { latest[levelId] = circuit.toDto() }
        flush?.cancel()
        flush = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            writePending()
        }
    }

    /** Applies [transform] to every saved draft, e.g. to follow a component whose pins moved. */
    suspend fun rewriteAll(transform: (Circuit) -> Circuit) {
        // Let a flush that already started writing finish first, so its drafts land before the
        // rewrite reads them; a flush still inside its debounce is cancelled and drained below.
        // Joining a cancelled flush rethrows its CancellationException, which belongs to the
        // flush and must not cancel this coroutine: swallow it unless we are cancelled ourselves.
        flush?.cancel()
        try {
            flush?.join()
        } catch (_: CancellationException) {
            coroutineContext.ensureActive()
        }
        val snapshot: Map<String, CircuitDto> = synchronized(lock) {
            latest.toMap().also { latest.clear() }
        }
        ds.updateString(KEY) { old ->
            val merged = decode(old) + snapshot
            json.encodeToString(merged.mapValues { (_, dto) -> transform(dto.toDomain()).toDto() })
        }
    }

    private suspend fun writePending() {
        val snapshot: Map<String, CircuitDto> = synchronized(lock) {
            if (latest.isEmpty()) return
            latest.toMap().also { latest.clear() }
        }
        // Past this point the snapshot lives only in this job: a cancel must not drop it.
        withContext(NonCancellable) {
            ds.updateString(KEY) { old -> json.encodeToString(decode(old) + snapshot) }
        }
    }

    private fun decode(raw: String?): Map<String, CircuitDto> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, CircuitDto>>(raw)
        } catch (ignored: SerializationException) {
            emptyMap()
        }
    }

    private companion object {
        const val KEY = "logicgate2_circuits"
        const val SAVE_DEBOUNCE_MS = 400L
    }
}

private fun PinRef.toDto() = PinDto(node.raw, side == PinSide.OUT, index)

private fun PinDto.toDomain() = PinRef(NodeId(node), if (out) PinSide.OUT else PinSide.IN, index)

private fun Node.toDto(): NodeDto = when (this) {
    is Gate -> NodeDto(id.raw, "gate", chip = chipId, x = pos.x, y = pos.y)
    is LevelInput -> NodeDto(id.raw, "in", index = index, x = pos.x, y = pos.y)
    is LevelOutput -> NodeDto(id.raw, "out", index = index, x = pos.x, y = pos.y)
}

private fun NodeDto.toDomain(): Node? {
    val p = Point(x, y)
    return when (kind) {
        "gate" -> chip?.let { Gate(NodeId(id), it, p) }
        "in" -> LevelInput(NodeId(id), index, p)
        "out" -> LevelOutput(NodeId(id), index, p)
        else -> null
    }
}

internal fun Circuit.toDto() = CircuitDto(
    nodes = nodes.map { it.toDto() },
    wires = wires.map { WireDto(it.from.toDto(), it.to.toDto()) },
    nextId = nextId,
)

internal fun CircuitDto.toDomain() = Circuit(
    nodes = nodes.mapNotNull { it.toDomain() },
    wires = wires.map { Wire(it.from.toDomain(), it.to.toDomain()) },
    nextId = nextId,
)
