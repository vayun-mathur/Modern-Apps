package com.vayunmathur.games.logicgate.platform

import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinChips
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.sim.Probe
import com.vayunmathur.games.logicgate.domain.sim.Scoring
import com.vayunmathur.games.logicgate.domain.sim.Verifier
import com.vayunmathur.games.logicgate.domain.sim.VerifyResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The open editor, for a level or a sandbox component: an undo/redo stack of immutable boards,
 * and after every committed edit a debounced re-verification on [compute] (levels) or a fresh
 * live probe (sandbox), cancelling any older run. [persist] saves each edit; [solved] fires
 * whenever a level's tests all pass.
 */
class EditorSession(
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher,
    private val persist: (GameUiState) -> Unit,
    private val solved: (GameUiState) -> Unit,
) : LogicActions {
    private val _state = MutableStateFlow<GameUiState?>(null)
    val state: StateFlow<GameUiState?> = _state.asStateFlow()

    private val history = ArrayDeque<Board>()
    private val future = ArrayDeque<Board>()
    private var verifyJob: Job? = null
    private var liveJob: Job? = null
    private var wasSolved = false

    /** Replaces whatever was open with [initial] (or nothing while it loads). */
    fun open(initial: GameUiState?) {
        verifyJob?.cancel()
        liveJob?.cancel()
        history.clear()
        future.clear()
        wasSolved = false
        _state.value = initial
        if (initial?.level != null) reverify(announce = false) else refreshLive()
    }

    /** A change outside the undo stack, such as a new best cost. */
    fun update(transform: (GameUiState) -> GameUiState) = _state.update { it?.let(transform) }

    override fun placeChip(chipId: String, at: Point) {
        val s = _state.value ?: return
        when {
            PinChips.isPin(chipId) && s.sandbox != null ->
                commit(s.board.addPin(PinChips.isInput(chipId), PinChips.width(chipId), at))
            s.chips.any { it.id == chipId } -> commit(s.board.copy(circuit = s.circuit.addGate(chipId, at).first))
        }
    }

    override fun moveNode(id: NodeId, to: Point) {
        val s = _state.value ?: return
        commit(s.board.copy(circuit = s.circuit.moveNode(id, to)))
    }

    override fun connect(a: PinRef, b: PinRef) {
        val s = _state.value ?: return
        s.circuit.connect(a, b, s.catalog.lookup(s.io))?.let { commit(s.board.copy(circuit = it)) }
    }

    override fun select(selection: Selection?) = update { it.copy(selection = selection) }

    override fun deleteSelection() {
        val s = _state.value ?: return
        when (val sel = s.selection) {
            is Selection.OfNode -> deleteNode(sel.id)
            is Selection.OfWire -> commit(s.board.copy(circuit = s.circuit.removeWire(sel.wire)))
            null -> Unit
        }
        update { it.copy(selection = null) }
    }

    override fun deleteNode(id: NodeId) {
        val s = _state.value ?: return
        when {
            s.circuit.node(id) is Gate -> commit(s.board.copy(circuit = s.circuit.removeNode(id)))
            s.sandbox != null -> commit(s.board.removePin(id))
        }
    }

    override fun undo() {
        val s = _state.value ?: return
        val previous = history.removeLastOrNull() ?: return
        future.addLast(s.board)
        show(s, previous)
    }

    override fun redo() {
        val s = _state.value ?: return
        val next = future.removeLastOrNull() ?: return
        history.addLast(s.board)
        show(s, next)
    }

    override fun setInput(index: Int, value: Int) {
        update { s ->
            s.copy(manualInputs = s.manualInputs.mapIndexed { i, v -> if (i == index) value else v }, probeCase = null)
        }
        refreshLive()
    }

    override fun probe(caseIndex: Int?) {
        update { it.copy(probeCase = caseIndex) }
        refreshLive()
    }

    override fun showBrief(show: Boolean) = update { it.copy(showBrief = show) }

    override fun showChipInfo(chip: ChipSpec?) = update { it.copy(infoChip = chip) }

    override fun dismissWin() = update { it.copy(won = false) }

    override fun renamePin(id: NodeId, name: String) {
        val s = _state.value ?: return
        if (s.sandbox != null) commit(s.board.renamePin(id, name))
    }

    override fun renameComponent(name: String, group: String) {
        val s = _state.value ?: return
        val mode = s.sandbox ?: return
        val next = mode.copy(name = name.trim().ifEmpty { mode.name }, group = group.trim().ifEmpty { mode.group })
        if (next == mode) return
        update { it.copy(mode = next) }
        _state.value?.let(persist)
    }

    private fun commit(next: Board) {
        val s = _state.value ?: return
        if (next == s.board) return
        history.addLast(s.board)
        if (history.size > UNDO_LIMIT) history.removeFirst()
        future.clear()
        show(s, next)
    }

    private fun show(s: GameUiState, board: Board) {
        val selection = when (val sel = s.selection) {
            is Selection.OfNode -> sel.takeIf { board.circuit.node(it.id) != null }
            is Selection.OfWire -> sel.takeIf { it.wire in board.circuit.wires }
            null -> null
        }
        val next = s.copy(
            board = board,
            manualInputs = carryInputs(s, board),
            selection = selection,
            canUndo = history.isNotEmpty(),
            canRedo = future.isNotEmpty(),
            cost = cost(s, board.circuit),
        )
        _state.value = next
        persist(next)
        if (next.level != null) reverify(announce = true) else refreshLive()
    }

    private fun reverify(announce: Boolean) {
        val s = _state.value ?: return
        val level = s.level ?: return
        verifyJob?.cancel()
        verifyJob = scope.launch {
            delay(VERIFY_DEBOUNCE_MS)
            val result = withContext(compute) { Verifier.verify(s.io, level.tests, s.circuit, s.catalog::get) }
            val now = _state.value?.takeIf { it.circuit == s.circuit } ?: return@launch
            val probed = now.copy(verify = result, probeCase = now.probeCase?.takeIf { it < result.total })
            val live = withContext(compute) { liveProbe(probed) }
            applyVerify(probed, result, live, announce)
        }
    }

    private fun applyVerify(probed: GameUiState, result: VerifyResult, live: Probe, announce: Boolean) {
        val level = probed.level ?: return
        val newlySolved = announce && result.solved && !wasSolved
        wasSolved = result.solved
        _state.update { cur ->
            if (cur?.circuit != probed.circuit) return@update cur
            cur.copy(
                verify = result,
                probeCase = probed.probeCase,
                live = if (cur.sameProbe(probed)) live else cur.live,
                stars = Scoring.stars(result.solved, cur.cost, level.par),
                won = cur.won || newlySolved,
            )
        }
        if (result.solved) solved(probed)
    }

    private fun refreshLive() {
        val s = _state.value ?: return
        liveJob?.cancel()
        liveJob = scope.launch {
            val live = withContext(compute) { liveProbe(s) }
            _state.update { cur ->
                if (cur?.circuit == s.circuit && cur.sameProbe(s)) cur.copy(live = live) else cur
            }
        }
    }

    private companion object {
        const val UNDO_LIMIT = 50
        const val VERIFY_DEBOUNCE_MS = 120L
    }
}

/** NAND cost of [circuit] with [s]'s chips. */
internal fun cost(s: GameUiState, circuit: Circuit): Int = Scoring.cost(circuit) { s.catalog[it]?.nandCost }

private fun liveProbe(s: GameUiState): Probe {
    val case = s.probeCase
    val level = s.level
    return if (level != null && case != null && case < (s.verify?.total ?: 0)) {
        Verifier.probe(s.io, level.tests, s.circuit, case, s.catalog::get)
    } else {
        Verifier.manual(s.io, s.circuit, s.manualInputs, s.catalog::get)
    }
}

private fun GameUiState.sameProbe(other: GameUiState) =
    probeCase == other.probeCase && manualInputs == other.manualInputs

/** Keeps each input node's manual value across edits that add, remove or reindex inputs. */
private fun carryInputs(s: GameUiState, board: Board): List<Int> {
    val byNode = s.circuit.nodes.filterIsInstance<LevelInput>()
        .associate { it.id to s.manualInputs.getOrElse(it.index) { 0 } }
    val values = IntArray(board.io.inputs.size)
    for (n in board.circuit.nodes) {
        if (n is LevelInput && n.index in values.indices) values[n.index] = byNode[n.id] ?: 0
    }
    return values.toList()
}
