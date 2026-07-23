package com.vayunmathur.games.logicgate.util

import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.logicgate.data.*
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

// Persisted format v2 with busWidth + lenient json
@Serializable
data class PersistedWire(val id: String, val fromInstance: String, val fromPin: Int, val toInstance: String, val toPin: Int, val busWidth: Int = 1)
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
    val draggedChipGhost: DraggedChipGhost? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

data class DraggedChipGhost(val chipId: String, val offset: Offset)

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
        val json = try { ctx.assets.open("achievements.json").bufferedReader().use { it.readText() } } catch (_: Exception) { "[]" }
        LogicAchievementsManager(ctx, json, repo)
    }

    private val _completedIds = MutableStateFlow(repo.getCompletedLevelIds())
    val completedIds: StateFlow<Set<String>> = _completedIds.asStateFlow()

    private val _unlockedChips = MutableStateFlow(repo.unlockedChipIds())
    val unlockedChips: StateFlow<Set<String>> = _unlockedChips.asStateFlow()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var allCircuits: MutableMap<String, Circuit> = mutableMapOf()
    private var persistJob: Job? = null
    private val persistMutex = Mutex()
    private val undoStacks: MutableMap<String, MutableList<Circuit>> = mutableMapOf()
    private val redoStacks: MutableMap<String, MutableList<Circuit>> = mutableMapOf()
    private val maxHistory = 24

    private val jsonLenient = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadAllCircuits()
            achievementsManager.checkExistingAchievements()
        }
    }

    private suspend fun loadAllCircuits() {
        val raw = ds.getString(KEY_CIRCUITS) ?: return
        try {
            val parsed = jsonLenient.decodeFromString<AllSavedCircuits>(raw)
            parsed.map.forEach { (lvlId, pc) -> allCircuits[lvlId] = pc.toCircuit() }
        } catch (_: Exception) {
            try {
                val parsedOld = Json.decodeFromString<AllSavedCircuits>(raw)
                parsedOld.map.forEach { (lvlId, pc) -> allCircuits[lvlId] = pc.toCircuit() }
            } catch (_: Exception) { }
        }
    }

    private fun saveAllCircuitsNow() {
        val toSave = AllSavedCircuits(allCircuits.mapValues { it.value.toPersisted() })
        val json = jsonLenient.encodeToString(toSave)
        viewModelScope.launch(Dispatchers.IO) {
            persistMutex.withLock { ds.setString(KEY_CIRCUITS, json) }
        }
    }

    private fun schedulePersist(delayMs: Long = 400L) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            persistMutex.withLock {
                val toSave = AllSavedCircuits(allCircuits.mapValues { it.value.toPersisted() })
                val json = jsonLenient.encodeToString(toSave)
                ds.setString(KEY_CIRCUITS, json)
            }
        }
    }

    fun selectLevel(levelId: String) {
        val loaded = allCircuits[levelId] ?: Circuit()
        undoStacks.getOrPut(levelId) { mutableListOf() }
        redoStacks.getOrPut(levelId) { mutableListOf() }
        val canUndo = undoStacks[levelId]?.isNotEmpty() == true
        val canRedo = redoStacks[levelId]?.isNotEmpty() == true
        _uiState.update { it.copy(currentLevelId=levelId, circuit=loaded, evalStatus=EvalStatus.Idle, wiringFrom=null, selectedGateInstanceId=null, dragGhostLineEnd=null, canUndo=canUndo, canRedo=canRedo) }
        evaluateCurrent()
    }

    fun selectChapter(id: ChapterId) { _uiState.update { it.copy(selectedChapter=id) } }

    fun addGate(chipId: String) { addGateAt(chipId, null, null) }

    fun addGateAt(chipId: String, x: Float?, y: Float?): String {
        val state = _uiState.value
        val newId = "G_${UUID.randomUUID().toString().take(6)}"
        val cnt = state.circuit.gates.size
        val px = (x ?: (80f + (cnt % 4) * 140f)).coerceIn(0f, 3000f)
        val py = (y ?: (100f + (cnt / 4) * 110f)).coerceIn(0f, 3000f)
        val gate = PlacedChip(newId, chipId, x=px, y=py)
        val newCircuit = state.circuit.copy(gates=state.circuit.gates + gate)
        pushHistory(state.circuit)
        updateCircuit(newCircuit, immediatePersist=true)
        return newId
    }

    fun removeGate(instanceId: String) {
        val s = _uiState.value.circuit
        pushHistory(s)
        val newGates = s.gates.filterNot { it.instanceId==instanceId }
        val newWires = s.wires.filterNot { it.from.instanceId==instanceId || it.to.instanceId==instanceId }
        val newOuts = s.outputMappings.filterNot { it.from.instanceId==instanceId }
        updateCircuit(s.copy(gates=newGates, wires=newWires, outputMappings=newOuts))
    }

    fun clearCircuit() {
        val s = _uiState.value.circuit
        if (s.gates.isEmpty() && s.wires.isEmpty() && s.outputMappings.isEmpty()) return
        pushHistory(s)
        updateCircuit(Circuit())
    }

    private fun pushHistory(c: Circuit) {
        val lvl = _uiState.value.currentLevelId ?: return
        val stack = undoStacks.getOrPut(lvl) { mutableListOf() }
        stack.add(c)
        if (stack.size > maxHistory) stack.removeAt(0)
        redoStacks[lvl]?.clear()
        _uiState.update { it.copy(canUndo=stack.isNotEmpty(), canRedo=false) }
    }

    fun undo() {
        val lvl = _uiState.value.currentLevelId ?: return
        val uStack = undoStacks[lvl] ?: return
        if (uStack.isEmpty()) return
        val rStack = redoStacks.getOrPut(lvl) { mutableListOf() }
        rStack.add(_uiState.value.circuit)
        val prev = uStack.removeAt(uStack.lastIndex)
        allCircuits[lvl]=prev
        _uiState.update { it.copy(circuit=prev, canUndo=uStack.isNotEmpty(), canRedo=rStack.isNotEmpty()) }
        schedulePersist()
        evaluateCurrent()
    }

    fun redo() {
        val lvl = _uiState.value.currentLevelId ?: return
        val rStack = redoStacks[lvl] ?: return
        if (rStack.isEmpty()) return
        val uStack = undoStacks.getOrPut(lvl) { mutableListOf() }
        uStack.add(_uiState.value.circuit)
        val next = rStack.removeAt(rStack.lastIndex)
        allCircuits[lvl]=next
        _uiState.update { it.copy(circuit=next, canUndo=uStack.isNotEmpty(), canRedo=rStack.isNotEmpty()) }
        schedulePersist()
        evaluateCurrent()
    }

    fun onGateMoved(instanceId: String, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val newGates = s.gates.map { if(it.instanceId==instanceId) it.copy(x=x.coerceIn(0f,3000f), y=y.coerceIn(0f,3000f)) else it }
        _uiState.update { it.copy(circuit=it.circuit.copy(gates=newGates)) }
    }
    fun onGateMoveFinished(instanceId: String, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val prevCircuit = allCircuits[_uiState.value.currentLevelId] ?: s
        // push history only if wasn't already pushed for this drag session
        if (undoStacks[_uiState.value.currentLevelId]?.lastOrNull() != prevCircuit) pushHistory(prevCircuit)
        val newGates = s.gates.map { if(it.instanceId==instanceId) it.copy(x=x.coerceIn(0f,3000f), y=y.coerceIn(0f,3000f)) else it }
        val newCircuit = s.copy(gates=newGates)
        val lvl = _uiState.value.currentLevelId
        if (lvl!=null) allCircuits[lvl]=newCircuit
        _uiState.update { it.copy(circuit=newCircuit) }
        schedulePersist()
        evaluateCurrent()
    }

    fun onInputMoved(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val newMap = s.inputPositions.toMutableMap()
        newMap[idx]=IoPos(x.coerceIn(0f,3000f), y.coerceIn(0f,3000f))
        _uiState.update { it.copy(circuit=s.copy(inputPositions=newMap)) }
    }
    fun onInputMoveFinished(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val prevCircuit = allCircuits[_uiState.value.currentLevelId] ?: s
        pushHistory(prevCircuit)
        val newMap = s.inputPositions.toMutableMap()
        newMap[idx]=IoPos(x.coerceIn(0f,3000f), y.coerceIn(0f,3000f))
        val newCircuit = s.copy(inputPositions=newMap)
        val lvl=_uiState.value.currentLevelId
        if(lvl!=null) allCircuits[lvl]=newCircuit
        _uiState.update { it.copy(circuit=newCircuit) }
        schedulePersist()
    }
    fun onOutputMoved(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val newMap = s.outputPositions.toMutableMap()
        newMap[idx]=IoPos(x.coerceIn(0f,3000f), y.coerceIn(0f,3000f))
        _uiState.update { it.copy(circuit=s.copy(outputPositions=newMap)) }
    }
    fun onOutputMoveFinished(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val prevCircuit = allCircuits[_uiState.value.currentLevelId] ?: s
        pushHistory(prevCircuit)
        val newMap = s.outputPositions.toMutableMap()
        newMap[idx]=IoPos(x.coerceIn(0f,3000f), y.coerceIn(0f,3000f))
        val newCircuit=s.copy(outputPositions=newMap)
        val lvl=_uiState.value.currentLevelId
        if(lvl!=null) allCircuits[lvl]=newCircuit
        _uiState.update { it.copy(circuit=newCircuit) }
        schedulePersist()
    }

    fun startWiring(from: WireEnd) { _uiState.update { it.copy(wiringFrom=from) } }
    fun cancelWiring() { _uiState.update { it.copy(wiringFrom=null, dragGhostLineEnd=null) } }
    fun updateGhostLine(end: Offset?) { _uiState.update { it.copy(dragGhostLineEnd=end) } }

    private fun outputPinWidth(end: WireEnd, level: LevelDef?): Int {
        if (end.instanceId.startsWith("__IN_")) {
            val idx = end.instanceId.removePrefix("__IN_").toIntOrNull() ?: return 1
            return level?.inputWidth(idx) ?: 1
        }
        val gate = _uiState.value.circuit.gates.find { it.instanceId==end.instanceId } ?: return 1
        return ChipLibrary.get(gate.chipId).outputPinWidth(end.pinIndex)
    }
    private fun inputPinWidth(end: WireEnd, level: LevelDef?): Int {
        if (end.instanceId.startsWith("__OUT_")) {
            val idx = end.instanceId.removePrefix("__OUT_").toIntOrNull() ?: return 1
            return level?.outputWidth(idx) ?: 1
        }
        val gate = _uiState.value.circuit.gates.find { it.instanceId==end.instanceId } ?: return 1
        return ChipLibrary.get(gate.chipId).inputPinWidth(end.pinIndex)
    }

    fun createWire(from: WireEnd, to: WireEnd) {
        val s = _uiState.value.circuit
        val lvlId = _uiState.value.currentLevelId
        val level = lvlId?.let { try { Levels.get(it) } catch(_:Exception){null} }
        if (from.instanceId==to.instanceId) { _uiState.update{it.copy(wiringFrom=null,dragGhostLineEnd=null)}; return }
        val fromIsOut = isOutputEnd(from)
        val toIsIn = isInputEnd(to)
        if (!fromIsOut || !toIsIn) { _uiState.update{it.copy(wiringFrom=null,dragGhostLineEnd=null)}; return }

        val srcW = outputPinWidth(from, level)
        val dstW = inputPinWidth(to, level)

        if (srcW != dstW) {
            val msg = when {
                srcW==8 && dstW==1 -> "Cannot wire BUS8[8] thick blue directly to 1-bit pin – use SPLIT_8 contractor to split BUS8 -> 8 bits, or use bus target."
                dstW==8 && srcW==1 -> "Cannot wire 1-bit thin green into BUS8[8] thick blue – use JOIN_8 expander (bits->BUS8[8]) to combine 8 bits into one bus wire (less tedious)."
                srcW==4 && dstW==1 -> "BUS4[4] orange thick -> bit: use SPLIT_4 contractor."
                dstW==4 && srcW==1 -> "Bit -> BUS4[4] orange: use JOIN_4 expander."
                else -> "Width mismatch: source ${srcW}b vs sink ${dstW}b. Use JOIN/SPLIT expander/contractor to convert between bus and bit."
            }
            _uiState.update { it.copy(evalStatus=EvalStatus.Error(msg), wiringFrom=null, dragGhostLineEnd=null) }
            return
        }

        pushHistory(s)
        if (to.instanceId.startsWith("__OUT_")) {
            val outIdx = to.instanceId.removePrefix("__OUT_").toIntOrNull() ?: run { _uiState.update{it.copy(wiringFrom=null,dragGhostLineEnd=null)}; return }
            val existing = s.outputMappings.filterNot { it.outputIndex==outIdx }
            val newMap = existing + OutputMapping(outIdx, from)
            updateCircuit(s.copy(outputMappings=newMap))
        } else {
            val existingWires = s.wires.filterNot { it.to==to }
            val newWire = Wire(id="W_${UUID.randomUUID().toString().take(6)}", from=from, to=to, busWidth=srcW)
            updateCircuit(s.copy(wires=existingWires + newWire))
        }
        _uiState.update { it.copy(wiringFrom=null, dragGhostLineEnd=null) }
    }

    private fun isOutputEnd(end: WireEnd): Boolean {
        if (end.instanceId.startsWith("__IN_")) return true
        if (end.instanceId.startsWith("__OUT_")) return false
        val gate = _uiState.value.circuit.gates.find { it.instanceId==end.instanceId } ?: return false
        val def = ChipLibrary.get(gate.chipId)
        return end.pinIndex in 0 until def.outputCount
    }
    private fun isInputEnd(end: WireEnd): Boolean {
        if (end.instanceId.startsWith("__OUT_")) return true
        if (end.instanceId.startsWith("__IN_")) return false
        val gate = _uiState.value.circuit.gates.find { it.instanceId==end.instanceId } ?: return false
        val def = ChipLibrary.get(gate.chipId)
        return end.pinIndex in 0 until def.inputCount
    }

    fun completeWiring(to: WireEnd) { val from=_uiState.value.wiringFrom ?: return; createWire(from,to) }

    fun removeWire(wireId: String) {
        val s=_uiState.value.circuit
        pushHistory(s)
        updateCircuit(s.copy(wires=s.wires.filterNot{it.id==wireId}))
    }
    fun removeOutputMapping(outIdx: Int) {
        val s=_uiState.value.circuit
        pushHistory(s)
        updateCircuit(s.copy(outputMappings=s.outputMappings.filterNot{it.outputIndex==outIdx}))
    }

    fun onChipPaletteDragStart(chipId: String, offset: Offset) { _uiState.update{it.copy(draggedChipGhost=DraggedChipGhost(chipId,offset))} }
    fun onChipPaletteDrag(offset: Offset) { _uiState.update{it.copy(draggedChipGhost=it.draggedChipGhost?.copy(offset=offset))} }
    fun onChipPaletteDragEnd(canvasX: Float?, canvasY: Float?) {
        val ghost=_uiState.value.draggedChipGhost
        _uiState.update{it.copy(draggedChipGhost=null)}
        if(ghost!=null && canvasX!=null && canvasY!=null) addGateAt(ghost.chipId, canvasX, canvasY)
    }

    private fun updateCircuit(newCircuit: Circuit, immediatePersist: Boolean=false) {
        val lvlId=_uiState.value.currentLevelId
        if(lvlId!=null) {
            allCircuits[lvlId]=newCircuit
            if(immediatePersist) saveAllCircuitsNow() else schedulePersist()
        }
        val lvl = lvlId
        val canUndoFlag = lvl?.let { undoStacks[it]?.isNotEmpty() } ?: false
        val canRedoFlag = lvl?.let { redoStacks[it]?.isNotEmpty() } ?: false
        _uiState.update{it.copy(circuit=newCircuit, canUndo=canUndoFlag, canRedo=canRedoFlag)}
        evaluateCurrent()
    }

    fun evaluateCurrent() {
        val state=_uiState.value
        val lvlId=state.currentLevelId ?: return
        val level = try { Levels.get(lvlId) } catch(_:Exception){ return }
        val circuit=state.circuit
        if(level.id=="COMPUTER") {
            val msg = evaluateComputerLevel(circuit)
            if(msg==null) {
                val evalResult = CircuitEvaluator.evaluate(level, circuit)
                when(evalResult){
                    is EvalResult.Success -> {
                        _uiState.update{it.copy(evalStatus=EvalStatus.Ok(passingRows=evalResult.rows.size, totalRows=evalResult.rows.size, isFullyCorrect=true, failingRows=emptyList()))}
                        onLevelWon(level, circuit)
                    }
                    is EvalResult.Error -> _uiState.update{it.copy(evalStatus=EvalStatus.Error(evalResult.message))}
                    is EvalResult.Cycle -> {
                        _uiState.update{it.copy(evalStatus=EvalStatus.Ok(passingRows=1, totalRows=1, isFullyCorrect=true, failingRows=emptyList()))}
                        onLevelWon(level, circuit)
                    }
                }
            } else {
                _uiState.update{it.copy(evalStatus=EvalStatus.Error(msg))}
            }
            return
        }
        val evalResult = CircuitEvaluator.evaluate(level, circuit)
        val (fullyCorrect, failing) = CircuitEvaluator.isCorrect(level, circuit)
        when(evalResult){
            is EvalResult.Error -> _uiState.update{it.copy(evalStatus=EvalStatus.Error(evalResult.message))}
            is EvalResult.Cycle -> _uiState.update{it.copy(evalStatus=EvalStatus.Cycle(evalResult.ids))}
            is EvalResult.Success -> {
                val total = if(level.totalInputBits<=10) (1 shl level.totalInputBits) else evalResult.rows.size
                val passing = total - failing.size
                _uiState.update{it.copy(evalStatus=EvalStatus.Ok(passing, total, fullyCorrect, failing))}
                if(fullyCorrect) onLevelWon(level, circuit)
            }
        }
    }

    private fun evaluateComputerLevel(circuit: Circuit): String? {
        val ids = circuit.gates.map{it.chipId}.toSet()
        if(!ids.contains("CPU") && !ids.contains("CPU_8")) return "Add CPU bus chip – it does fetch-decode-execute from RAM. It needs OPCODE[4] orange bus via SPLIT_4 and ADDR[8] blue bus."
        if(!ids.contains("RAM_256B") && !ids.contains("RAM_256")) return "Add RAM_256B Main Memory [8] – unified 256x8 where program lives alongside data (von Neumann)."
        if(circuit.wires.size < 2) return "Wire CPU <-> RAM with BUS wires: CPU.PC[8] blue thick -> MUX_B8 A[8] (fetch phase), CPU.ADDR_M[8] blue -> MUX_B8 B[8] (data phase). MUX_B8 OUT[8] blue -> RAM ADDR[8]. Use bus [8] wires."
        val hasBusWire = circuit.wires.any{it.busWidth==8}
        if(!hasBusWire) return "Use 8-bit BUS wires [8] thick blue – they cut tedium from 8 thin green wires to 1 thick. Use JOIN_8 expander to build bus."
        if(!ids.contains("MUX_B8") && !ids.contains("MUX4_B8") && !ids.contains("MUX8_B8")) return "Add MUX_B8 bus address MUX – it chooses PC[8] for fetch vs ADDR_M[8] for data – key for shared RAM program-from-RAM."
        if (!ids.contains("SPLIT_4") && !ids.contains("JOIN_4")) return "Add SPLIT_4 contractor for opcode decode – CPU needs OPCODE[4] orange bus via SPLIT_4 from RAM data."
        if(circuit.outputMappings.isEmpty()) return "Connect final OUT[8] bus – drag from RAM or CPU output bus dot to output terminal."
        return null
    }

    private fun onLevelWon(level: LevelDef, circuit: Circuit) {
        viewModelScope.launch{
            withContext(Dispatchers.IO){ repo.markCompleted(level.id) }
            _completedIds.value=repo.getCompletedLevelIds()
            _unlockedChips.value=repo.unlockedChipIds()
            repo.incCircuitsChecked()
            achievementsManager.onAchievementUnlocked("first_gate")
            val allCompleted=_completedIds.value
            achievementsManager.onProgressUpdated("all_levels", repo.totalCompleted())
            Levels.chapters.forEach{ ch ->
                val cnt=allCompleted.count{Levels.byId[it]?.chapter==ch.id}
                val key=when(ch.id){ChapterId.FOUNDATION->"foundation_complete"; ChapterId.ROUTING->"routing_complete"; ChapterId.ARITH->"arith_complete"; ChapterId.MEMORY->"memory_complete"; ChapterId.CPU->"cpu_complete"}
                achievementsManager.onProgressUpdated(key,cnt)
            }
        }
    }

    fun toggleTruthTable(){ _uiState.update{it.copy(showTruthTable=!it.showTruthTable)} }
    fun dismissAchievement()=achievementsManager.dismissNotification()

    companion object { private const val KEY_CIRCUITS="logicgate_circuits_v1" }
}

private fun PersistedCircuit.toCircuit(): Circuit {
    return Circuit(
        gates=gates.map{PlacedChip(it.instanceId,it.chipId,it.x,it.y)},
        wires=wires.map{Wire(it.id, WireEnd(it.fromInstance,it.fromPin), WireEnd(it.toInstance,it.toPin), busWidth=it.busWidth.coerceIn(1,8))},
        outputMappings=outputs.map{OutputMapping(it.outputIndex, WireEnd(it.fromInstance,it.fromPin))},
        inputPositions=inputPos.mapValues{IoPos(it.value.x,it.value.y)},
        outputPositions=outputPos.mapValues{IoPos(it.value.x,it.value.y)}
    )
}
private fun Circuit.toPersisted(): PersistedCircuit {
    return PersistedCircuit(
        gates=gates.map{PersistedGate(it.instanceId,it.chipId,it.x,it.y)},
        wires=wires.map{PersistedWire(it.id,it.from.instanceId,it.from.pinIndex,it.to.instanceId,it.to.pinIndex, busWidth=it.busWidth)},
        outputs=outputMappings.map{PersistedOutputMap(it.outputIndex,it.from.instanceId,it.from.pinIndex)},
        inputPos=inputPositions.mapValues{PersistedIoPos(it.value.x,it.value.y)},
        outputPos=outputPositions.mapValues{PersistedIoPos(it.value.x,it.value.y)}
    )
}
