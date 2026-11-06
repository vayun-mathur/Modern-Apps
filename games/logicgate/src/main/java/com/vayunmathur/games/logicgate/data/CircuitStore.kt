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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 */
class CircuitStore(context: Context, dispatcher: CoroutineDispatcher) {
    private val ds = DataStoreUtils.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val json = Json { ignoreUnknownKeys = true }
    private var pending: Job? = null

    suspend fun load(levelId: String): Circuit? = decode(ds.getStringAwait(KEY))[levelId]?.toDomain()

    fun save(levelId: String, circuit: Circuit) {
        pending?.cancel()
        pending = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val dto = circuit.toDto()
            ds.updateString(KEY) { old -> json.encodeToString(decode(old) + (levelId to dto)) }
        }
    }

    /** Applies [transform] to every saved draft, e.g. to follow a component whose pins moved. */
    suspend fun rewriteAll(transform: (Circuit) -> Circuit) {
        ds.updateString(KEY) { old ->
            json.encodeToString(decode(old).mapValues { (_, dto) -> transform(dto.toDomain()).toDto() })
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
