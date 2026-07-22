package com.vayunmathur.games.logicgate.util

import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.logicgate.data.*
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

// ----- Persisted format -----
@Serializable
data class PersistedWire(val id: String, val fromInstance: String, val fromPin: Int, val toInstance: String, val toPin: Int)
@Serializable
data class PersistedGate(val instanceId: String, val chipId: String, val x: Float, val y: Float)
@Serializable
data class PersistedOutputMap(val outputIndex: Int, val fromInstance: String, val fromPin: Int)
@Serializable
data class PersistedIoPos(val x: Float, val y: Float)
@Serializable
data class PersistedCircuit(
    val gates: List<PersistedGate>,
    val wires: List<PersistedWire>,
    val outputs: List<PersistedOutputMap>,
    val inputPos: Map<Int, PersistedIoPos> = emptyMap(),
    val outputPos: Map<Int, PersistedIoPos> = emptyMap()
)
@Serializable
data class AllSavedCircuits(val map: Map<String, PersistedCircuit> = emptyMap())

data class UiState(
    val selectedChapter: ChapterId = ChapterId.FOUNDATION,
    val currentLevelId: String? = null,
    val circuit: Circuit = Circuit(),
    val evalStatus: EvalStatus = EvalStatus.Idle,
    val selectedGateInstanceId: String? = null,
    val wiringFrom: WireEnd? = null,
    val dragGhostLineEnd: Offset? = null,
    val showTruthTable: Boolean = true,
    val draggedChipGhost: DraggedChipGhost? = null
)

data class DraggedChipGhost(
    val chipId: String,
    val offset: Offset
)

sealed class EvalStatus {
    object Idle : EvalStatus()
    data class Ok(val passingRows: Int, val totalRows: Int, val isFullyCorrect: Boolean, val failingRows: List<Int>) : EvalStatus()
    data class Error(val msg: String) : EvalStatus()
    data class Cycle(val ids: List<String>) : EvalStatus()
}

class LogicViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = LogicProgressRepository(application)
    private val ds = DataStoreUtils.getInstance(application)
    private val ctx: Context get() = getApplication()

    val achievementsManager: LogicAchievementsManager = run {
        val json = try {
            ctx.assets.open("achievements.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) { "[]" }
        LogicAchievementsManager(ctx, json, repo)
    }

    private val _completedIds = MutableStateFlow(repo.getCompletedLevelIds())
    val completedIds: StateFlow<Set<String>> = _completedIds.asStateFlow()

    private val _unlockedChips = MutableStateFlow(repo.unlockedChipIds())
    val unlockedChips: StateFlow<Set<String>> = _unlockedChips.asStateFlow()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var allCircuits: MutableMap<String, Circuit> = mutableMapOf()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadAllCircuits()
            achievementsManager.checkExistingAchievements()
        }
    }

    private fun loadAllCircuits() {
        val raw = ds.getString(KEY_CIRCUITS) ?: return
        try {
            val parsed = Json.decodeFromString<AllSavedCircuits>(raw)
            parsed.map.forEach { (lvlId, pc) ->
                allCircuits[lvlId] = pc.toCircuit()
            }
        } catch (_: Exception) { }
    }

    private fun saveAllCircuits() {
        val toSave = AllSavedCircuits(allCircuits.mapValues { it.value.toPersisted() })
        val json = Json.encodeToString(toSave)
        viewModelScope.launch(Dispatchers.IO) {
            ds.setString(KEY_CIRCUITS, json)
        }
    }

    fun selectLevel(levelId: String) {
        val loaded = allCircuits[levelId] ?: Circuit()
        _uiState.update {
            it.copy(currentLevelId = levelId, circuit = loaded, evalStatus = EvalStatus.Idle, wiringFrom = null, selectedGateInstanceId = null, dragGhostLineEnd = null)
        }
        evaluateCurrent()
    }

    fun selectChapter(id: ChapterId) {
        _uiState.update { it.copy(selectedChapter = id) }
    }

    // --- Gates ---
    fun addGate(chipId: String) {
        addGateAt(chipId, null, null)
    }

    fun addGateAt(chipId: String, x: Float?, y: Float?): String {
        val state = _uiState.value
        val newId = "G_${UUID.randomUUID().toString().take(6)}"
        val existingCount = state.circuit.gates.size
        val px = x ?: (80f + (existingCount % 4) * 140f)
        val py = y ?: (100f + (existingCount / 4) * 110f)
        val gate = PlacedChip(newId, chipId, x = px, y = py)
        val newCircuit = state.circuit.copy(gates = state.circuit.gates + gate)
        updateCircuit(newCircuit)
        return newId
    }

    fun removeGate(instanceId: String) {
        val s = _uiState.value.circuit
        val newGates = s.gates.filterNot { it.instanceId == instanceId }
        val newWires = s.wires.filterNot { it.from.instanceId == instanceId || it.to.instanceId == instanceId }
        val newOuts = s.outputMappings.filterNot { it.from.instanceId == instanceId }
        updateCircuit(s.copy(gates = newGates, wires = newWires, outputMappings = newOuts))
    }

    fun clearCircuit() {
        updateCircuit(Circuit())
    }

    fun onGateMoved(instanceId: String, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val newGates = s.gates.map { if (it.instanceId == instanceId) it.copy(x = x, y = y) else it }
        val lvlId = _uiState.value.currentLevelId
        if (lvlId != null) {
            allCircuits[lvlId] = s.copy(gates = newGates)
            saveAllCircuits()
        }
        _uiState.update { it.copy(circuit = it.circuit.copy(gates = newGates)) }
    }

    // --- IO terminal moves ---
    fun onInputMoved(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val newMap = s.inputPositions.toMutableMap()
        newMap[idx] = IoPos(x, y)
        val newCircuit = s.copy(inputPositions = newMap)
        val lvlId = _uiState.value.currentLevelId
        if (lvlId != null) {
            allCircuits[lvlId] = newCircuit
            saveAllCircuits()
        }
        _uiState.update { it.copy(circuit = newCircuit) }
    }

    fun onOutputMoved(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val newMap = s.outputPositions.toMutableMap()
        newMap[idx] = IoPos(x, y)
        val newCircuit = s.copy(outputPositions = newMap)
        val lvlId = _uiState.value.currentLevelId
        if (lvlId != null) {
            allCircuits[lvlId] = newCircuit
            saveAllCircuits()
        }
        _uiState.update { it.copy(circuit = newCircuit) }
    }

    // --- Wiring ---
    fun startWiring(from: WireEnd) {
        _uiState.update { it.copy(wiringFrom = from) }
    }

    fun cancelWiring() {
        _uiState.update { it.copy(wiringFrom = null, dragGhostLineEnd = null) }
    }

    fun updateGhostLine(end: Offset?) {
        _uiState.update { it.copy(dragGhostLineEnd = end) }
    }

    fun createWire(from: WireEnd, to: WireEnd) {
        val s = _uiState.value.circuit
        if (from.instanceId == to.instanceId) {
            _uiState.update { it.copy(wiringFrom = null, dragGhostLineEnd = null) }
            return
        }
        // validate direction: from must be output (source), to must be input (sink)
        val fromIsOutput = isOutputEnd(from)
        val toIsInput = isInputEnd(to)
        if (!fromIsOutput || !toIsInput) {
            // try swapped?
            // Only allow output -> input, not input -> output or output -> output etc.
            _uiState.update { it.copy(wiringFrom = null, dragGhostLineEnd = null) }
            return
        }

        if (to.instanceId.startsWith("__OUT_")) {
            val outIdx = to.instanceId.removePrefix("__OUT_").toIntOrNull() ?: run {
                _uiState.update { it.copy(wiringFrom = null, dragGhostLineEnd = null) }; return
            }
            val existing = s.outputMappings.filterNot { it.outputIndex == outIdx }
            val newMap = existing + OutputMapping(outIdx, from)
            updateCircuit(s.copy(outputMappings = newMap))
        } else {
            val existingWires = s.wires.filterNot { it.to == to }
            val newWire = Wire(
                id = "W_${UUID.randomUUID().toString().take(6)}",
                from = from,
                to = to
            )
            updateCircuit(s.copy(wires = existingWires + newWire))
        }
        _uiState.update { it.copy(wiringFrom = null, dragGhostLineEnd = null) }
    }

    // classify ends
    private fun isOutputEnd(end: WireEnd): Boolean {
        if (end.instanceId.startsWith("__IN_")) return true // input terminals are sources
        if (end.instanceId.startsWith("__OUT_")) return false
        // gate: determine by checking chip def
        val gate = _uiState.value.circuit.gates.find { it.instanceId == end.instanceId } ?: return false
        val def = ChipLibrary.get(gate.chipId)
        return end.pinIndex in 0 until def.outputCount
    }
    private fun isInputEnd(end: WireEnd): Boolean {
        if (end.instanceId.startsWith("__OUT_")) return true
        if (end.instanceId.startsWith("__IN_")) return false
        val gate = _uiState.value.circuit.gates.find { it.instanceId == end.instanceId } ?: return false
        val def = ChipLibrary.get(gate.chipId)
        return end.pinIndex in 0 until def.inputCount
    }

    // backward compat
    fun completeWiring(to: WireEnd) {
        val from = _uiState.value.wiringFrom ?: return
        createWire(from, to)
    }

    fun removeWire(wireId: String) {
        val s = _uiState.value.circuit
        updateCircuit(s.copy(wires = s.wires.filterNot { it.id == wireId }))
    }

    fun removeOutputMapping(outIdx: Int) {
        val s = _uiState.value.circuit
        updateCircuit(s.copy(outputMappings = s.outputMappings.filterNot { it.outputIndex == outIdx }))
    }

    // --- Drag ghost for palette ---
    fun onChipPaletteDragStart(chipId: String, offset: Offset) {
        _uiState.update { it.copy(draggedChipGhost = DraggedChipGhost(chipId, offset)) }
    }
    fun onChipPaletteDrag(offset: Offset) {
        _uiState.update { it.copy(draggedChipGhost = it.draggedChipGhost?.copy(offset = offset)) }
    }
    fun onChipPaletteDragEnd(canvasX: Float?, canvasY: Float?) {
        val ghost = _uiState.value.draggedChipGhost
        _uiState.update { it.copy(draggedChipGhost = null) }
        if (ghost != null && canvasX != null && canvasY != null) {
            addGateAt(ghost.chipId, canvasX, canvasY)
        }
    }

    private fun updateCircuit(newCircuit: Circuit) {
        val lvlId = _uiState.value.currentLevelId
        if (lvlId != null) {
            allCircuits[lvlId] = newCircuit
            saveAllCircuits()
        }
        _uiState.update { it.copy(circuit = newCircuit) }
        evaluateCurrent()
    }

    fun evaluateCurrent() {
        val state = _uiState.value
        val lvlId = state.currentLevelId ?: return
        val level = Levels.get(lvlId)
        val circuit = state.circuit
        val evalResult = CircuitEvaluator.evaluate(level, circuit)
        val (fullyCorrect, failing) = CircuitEvaluator.isCorrect(level, circuit)
        when (evalResult) {
            is EvalResult.Error -> _uiState.update { it.copy(evalStatus = EvalStatus.Error(evalResult.message)) }
            is EvalResult.Cycle -> _uiState.update { it.copy(evalStatus = EvalStatus.Cycle(evalResult.ids)) }
            is EvalResult.Success -> {
                val total = if (level.inputs.size <= 10) (1 shl level.inputs.size) else evalResult.rows.size
                val passing = total - failing.size
                _uiState.update { it.copy(evalStatus = EvalStatus.Ok(passing, total, fullyCorrect, failing)) }
                if (fullyCorrect) {
                    onLevelWon(level, circuit)
                }
            }
        }
    }

    private fun onLevelWon(level: LevelDef, circuit: Circuit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.markCompleted(level.id)
            }
            _completedIds.value = repo.getCompletedLevelIds()
            _unlockedChips.value = repo.unlockedChipIds()

            repo.incCircuitsChecked()
            achievementsManager.onAchievementUnlocked("first_gate")
            val allCompleted = _completedIds.value
            achievementsManager.onProgressUpdated("all_levels", repo.totalCompleted())
            Levels.chapters.forEach { ch ->
                val count = allCompleted.count { Levels.byId[it]?.chapter == ch.id }
                val key = when (ch.id) {
                    ChapterId.FOUNDATION -> "foundation_complete"
                    ChapterId.ROUTING -> "routing_complete"
                    ChapterId.ARITH -> "arith_complete"
                    ChapterId.MEMORY -> "memory_complete"
                    ChapterId.CPU -> "cpu_complete"
                }
                achievementsManager.onProgressUpdated(key, count)
            }
        }
    }

    fun toggleTruthTable() {
        _uiState.update { it.copy(showTruthTable = !it.showTruthTable) }
    }

    fun dismissAchievement() = achievementsManager.dismissNotification()

    companion object {
        private const val KEY_CIRCUITS = "logicgate_circuits_v1"
    }
}

private fun PersistedCircuit.toCircuit(): Circuit {
    return Circuit(
        gates = gates.map { PlacedChip(it.instanceId, it.chipId, it.x, it.y) },
        wires = wires.map { Wire(it.id, WireEnd(it.fromInstance, it.fromPin), WireEnd(it.toInstance, it.toPin)) },
        outputMappings = outputs.map { OutputMapping(it.outputIndex, WireEnd(it.fromInstance, it.fromPin)) },
        inputPositions = inputPos.mapValues { IoPos(it.value.x, it.value.y) },
        outputPositions = outputPos.mapValues { IoPos(it.value.x, it.value.y) }
    )
}
private fun Circuit.toPersisted(): PersistedCircuit {
    return PersistedCircuit(
        gates = gates.map { PersistedGate(it.instanceId, it.chipId, it.x, it.y) },
        wires = wires.map { PersistedWire(it.id, it.from.instanceId, it.from.pinIndex, it.to.instanceId, it.to.pinIndex) },
        outputs = outputMappings.map { PersistedOutputMap(it.outputIndex, it.from.instanceId, it.from.pinIndex) },
        inputPos = inputPositions.mapValues { PersistedIoPos(it.value.x, it.value.y) },
        outputPos = outputPositions.mapValues { PersistedIoPos(it.value.x, it.value.y) }
    )
}
