package com.vayunmathur.games.logicgate.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.logicgate.data.CircuitStore
import com.vayunmathur.games.logicgate.data.ProgressStore
import com.vayunmathur.games.logicgate.domain.levels.LevelDef
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.sim.Probe
import com.vayunmathur.games.logicgate.domain.sim.Scoring
import com.vayunmathur.games.logicgate.domain.sim.Verifier
import com.vayunmathur.games.logicgate.domain.sim.VerifyResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns progression and the open level's editor: an undo/redo stack of immutable circuits, and a
 * debounced re-verification on [compute] after every committed edit, cancelling any older run.
 */
class LogicViewModel @JvmOverloads constructor(
    application: Application,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application), LogicActions {

    private val circuits = CircuitStore(application, io)
    private val progress = ProgressStore(application)

    val achievementsManager: LogicAchievements = LogicAchievements(
        application,
        application.assets.open("achievements.json").bufferedReader().use { it.readText() },
        progress,
    )

    private val _progression = MutableStateFlow(ProgressionUiState())
    val progression: StateFlow<ProgressionUiState> = _progression.asStateFlow()

    private val _game = MutableStateFlow<GameUiState?>(null)
    val game: StateFlow<GameUiState?> = _game.asStateFlow()

    private val history = ArrayDeque<Circuit>()
    private val future = ArrayDeque<Circuit>()
    private var verifyJob: Job? = null
    private var liveJob: Job? = null
    private var wasSolved = false
    private var fitToken = 0

    init {
        viewModelScope.launch {
            refreshProgression()
            withContext(io) { achievementsManager.checkExistingAchievements() }
        }
    }

    fun dismissAchievement() = achievementsManager.dismissNotification()

    fun openLevel(id: String) {
        if (_game.value?.level?.id == id) return
        val level = LevelRegistry[id] ?: return
        verifyJob?.cancel()
        liveJob?.cancel()
        history.clear()
        future.clear()
        _game.value = null
        viewModelScope.launch {
            val saved = withContext(io) { circuits.load(id) }
            val firstVisit = withContext(io) { progress.markBriefSeen(id) }
            val circuit = (saved ?: Circuit.forLevel(level.io)).sanitized(level.io, Chips.lookup(level.io))
            _game.value = GameUiState(
                level = level,
                circuit = circuit,
                chips = LevelRegistry.allowedChips(id).mapNotNull { Chips[it] },
                manualInputs = List(level.inputs.size) { 0 },
                cost = cost(circuit),
                bestCost = _progression.value.levels.firstOrNull { it.id == id }?.bestCost,
                showBrief = firstVisit,
                fitToken = ++fitToken,
                nextLevelId = LevelRegistry.next(id)?.id,
            )
            wasSolved = false
            reverify(announce = false)
        }
    }

    override fun placeChip(chipId: String, at: Point) {
        val s = _game.value ?: return
        if (s.chips.any { it.id == chipId }) commit(s.circuit.addGate(chipId, at).first)
    }

    override fun moveNode(id: NodeId, to: Point) {
        val s = _game.value ?: return
        commit(s.circuit.moveNode(id, to))
    }

    override fun connect(a: PinRef, b: PinRef) {
        val s = _game.value ?: return
        s.circuit.connect(a, b, Chips.lookup(s.level.io))?.let(::commit)
    }

    override fun select(selection: Selection?) {
        _game.update { it?.copy(selection = selection) }
    }

    override fun deleteSelection() {
        val s = _game.value ?: return
        val next = when (val sel = s.selection) {
            is Selection.OfNode -> s.circuit.removeNode(sel.id)
            is Selection.OfWire -> s.circuit.removeWire(sel.wire)
            null -> return
        }
        _game.update { it?.copy(selection = null) }
        commit(next)
    }

    override fun deleteNode(id: NodeId) {
        val s = _game.value ?: return
        commit(s.circuit.removeNode(id))
    }

    override fun undo() {
        val s = _game.value ?: return
        val previous = history.removeLastOrNull() ?: return
        future.addLast(s.circuit)
        show(s, previous)
    }

    override fun redo() {
        val s = _game.value ?: return
        val next = future.removeLastOrNull() ?: return
        history.addLast(s.circuit)
        show(s, next)
    }

    override fun setInput(index: Int, value: Int) {
        _game.update { s ->
            s?.copy(manualInputs = s.manualInputs.mapIndexed { i, v -> if (i == index) value else v }, probeCase = null)
        }
        refreshLive()
    }

    override fun probe(caseIndex: Int?) {
        _game.update { it?.copy(probeCase = caseIndex) }
        refreshLive()
    }

    override fun showBrief(show: Boolean) {
        _game.update { it?.copy(showBrief = show) }
    }

    override fun showChipInfo(chip: ChipSpec?) {
        _game.update { it?.copy(infoChip = chip) }
    }

    override fun dismissWin() {
        _game.update { it?.copy(won = false) }
    }

    private fun commit(next: Circuit) {
        val s = _game.value ?: return
        if (next == s.circuit) return
        history.addLast(s.circuit)
        if (history.size > UNDO_LIMIT) history.removeFirst()
        future.clear()
        show(s, next)
    }

    private fun show(s: GameUiState, circuit: Circuit) {
        val selection = when (val sel = s.selection) {
            is Selection.OfNode -> sel.takeIf { circuit.node(it.id) != null }
            is Selection.OfWire -> sel.takeIf { it.wire in circuit.wires }
            null -> null
        }
        _game.value = s.copy(
            circuit = circuit,
            selection = selection,
            canUndo = history.isNotEmpty(),
            canRedo = future.isNotEmpty(),
            cost = cost(circuit),
        )
        circuits.save(s.level.id, circuit)
        reverify(announce = true)
    }

    private fun reverify(announce: Boolean) {
        val s = _game.value ?: return
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch {
            delay(VERIFY_DEBOUNCE_MS)
            val result = withContext(compute) { Verifier.verify(s.level.io, s.level.tests, s.circuit) }
            val now = _game.value?.takeIf { it.circuit == s.circuit } ?: return@launch
            val probed = now.copy(verify = result, probeCase = now.probeCase?.takeIf { it < result.total })
            val live = withContext(compute) { liveProbe(probed) }
            applyVerify(probed, result, live, announce)
        }
    }

    private fun applyVerify(probed: GameUiState, result: VerifyResult, live: Probe, announce: Boolean) {
        val newlySolved = announce && result.solved && !wasSolved
        wasSolved = result.solved
        _game.update { cur ->
            if (cur?.circuit != probed.circuit) return@update cur
            cur.copy(
                verify = result,
                probeCase = probed.probeCase,
                live = if (cur.sameProbe(probed)) live else cur.live,
                stars = Scoring.stars(result.solved, cur.cost, cur.level.par),
                won = cur.won || newlySolved,
            )
        }
        if (result.solved) recordSolve(probed.level, probed.cost)
    }

    private fun recordSolve(level: LevelDef, cost: Int) {
        viewModelScope.launch {
            withContext(io) {
                progress.record(level.id, cost)
                achievementsManager.checkExistingAchievements()
            }
            refreshProgression()
        }
    }

    private suspend fun refreshProgression() {
        val best = withContext(io) { progress.bestCosts() }
        _progression.value = ProgressionUiState(
            LevelRegistry.levels.mapIndexed { i, l ->
                LevelCard(
                    id = l.id,
                    number = i + 1,
                    title = l.title,
                    chapter = l.chapter,
                    unlocked = LevelRegistry.isUnlocked(l.id, best.keys),
                    stars = best[l.id]?.let { Scoring.stars(solved = true, cost = it, par = l.par) } ?: 0,
                    bestCost = best[l.id],
                    par = l.par,
                )
            },
        )
        _game.update { s -> s?.copy(bestCost = best[s.level.id]) }
    }

    private fun refreshLive() {
        val s = _game.value ?: return
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            val live = withContext(compute) { liveProbe(s) }
            _game.update { cur ->
                if (cur?.circuit == s.circuit && cur.sameProbe(s)) cur.copy(live = live) else cur
            }
        }
    }

    private fun liveProbe(s: GameUiState): Probe {
        val case = s.probeCase
        val total = s.verify?.total ?: 0
        return if (case != null && case < total) {
            Verifier.probe(s.level.io, s.level.tests, s.circuit, case)
        } else {
            Verifier.manual(s.level.io, s.circuit, s.manualInputs)
        }
    }

    private fun GameUiState.sameProbe(other: GameUiState) =
        probeCase == other.probeCase && manualInputs == other.manualInputs

    private fun cost(circuit: Circuit) = Scoring.cost(circuit) { Chips[it]?.nandCost }

    private companion object {
        const val UNDO_LIMIT = 50
        const val VERIFY_DEBOUNCE_MS = 120L
    }
}
