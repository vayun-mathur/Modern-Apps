package com.vayunmathur.games.logicgate.data

import android.content.Context
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.CustomChip
import com.vayunmathur.games.logicgate.domain.model.LevelIo
import com.vayunmathur.games.logicgate.domain.model.Pin
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

/** Saved pin of a component's board. */
@Serializable
data class PinSpecDto(val name: String, val width: Int)

/** Saved sandbox component. */
@Serializable
data class ComponentDto(
    val id: String,
    val name: String,
    val group: String,
    val inputs: List<PinSpecDto>,
    val outputs: List<PinSpecDto>,
    val circuit: CircuitDto,
)

/**
 * The player's sandbox components, as one JSON list in the shared DataStore. Like [CircuitStore],
 * saves are debounced on the store's own scope so the last edit lands after the screen closes.
 */
class ComponentStore(context: Context, dispatcher: CoroutineDispatcher) {
    private val ds = DataStoreUtils.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val json = Json { ignoreUnknownKeys = true }
    private var pending: Job? = null

    suspend fun load(): List<CustomChip> = decode(ds.getStringAwait(KEY)).map { it.toDomain() }

    fun save(components: List<CustomChip>) {
        pending?.cancel()
        pending = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val dto = components.map { it.toDto() }
            ds.updateString(KEY) { json.encodeToString(dto) }
        }
    }

    private fun decode(raw: String?): List<ComponentDto> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString<List<ComponentDto>>(raw)
        } catch (ignored: SerializationException) {
            emptyList()
        }
    }

    private companion object {
        const val KEY = "logicgate2_components"
        const val SAVE_DEBOUNCE_MS = 400L
    }
}

private fun Pin.toDto() = PinSpecDto(name, width)

private fun PinSpecDto.toDomain() = Pin(name, width)

private fun CustomChip.toDto() = ComponentDto(
    id = id,
    name = name,
    group = group,
    inputs = board.io.inputs.map { it.toDto() },
    outputs = board.io.outputs.map { it.toDto() },
    circuit = board.circuit.toDto(),
)

private fun ComponentDto.toDomain() = CustomChip(
    id = id,
    name = name,
    group = group,
    board = Board(circuit.toDomain(), LevelIo(inputs.map { it.toDomain() }, outputs.map { it.toDomain() })),
)
