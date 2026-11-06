package com.vayunmathur.games.logicgate.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.logicgate.data.CircuitStore
import com.vayunmathur.games.logicgate.data.ComponentStore
import com.vayunmathur.games.logicgate.data.ProgressStore
import com.vayunmathur.games.logicgate.domain.levels.Chapter
import com.vayunmathur.games.logicgate.domain.levels.ChipGroups
import com.vayunmathur.games.logicgate.domain.levels.LevelDef
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.levels.TrayGroup
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.CustomChip
import com.vayunmathur.games.logicgate.domain.model.PinChips
import com.vayunmathur.games.logicgate.domain.model.pinRemap
import com.vayunmathur.games.logicgate.domain.model.remapChipPins
import com.vayunmathur.games.logicgate.domain.sim.Scoring
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Owns progression, the player's sandbox components, and the open [editor]. Components are kept
 * in memory and saved as a whole; when an edit moves a component's pins, every circuit that uses
 * it is rewired to follow.
 */
class LogicViewModel @JvmOverloads constructor(
    application: Application,
    compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {

    private val circuits = CircuitStore(application, io)
    private val components = ComponentStore(application, io)
    private val progress = ProgressStore(application)

    val achievementsManager: LogicAchievements = LogicAchievements(
        application,
        application.assets.open("achievements.json").bufferedReader().use { it.readText() },
        progress,
    )

    val editor = EditorSession(viewModelScope, compute, ::persist, ::recordSolve)
    val game: StateFlow<GameUiState?> = editor.state

    private val _progression = MutableStateFlow(ProgressionUiState())
    val progression: StateFlow<ProgressionUiState> = _progression.asStateFlow()

    private val _sandbox = MutableStateFlow(SandboxUiState())
    val sandbox: StateFlow<SandboxUiState> = _sandbox.asStateFlow()

    private val _catalog = MutableStateFlow(ChipCatalog.Builtin)
    val catalog: StateFlow<ChipCatalog> = _catalog.asStateFlow()

    private var completed: Set<String> = emptySet()
    private var fitToken = 0
    private var justCreated: String? = null

    init {
        viewModelScope.launch {
            setCustoms(withContext(io) { components.load() }, save = false)
            refreshProgression()
            withContext(io) { achievementsManager.checkExistingAchievements() }
        }
    }

    fun dismissAchievement() = achievementsManager.dismissNotification()

    fun openLevel(id: String) {
        if (game.value?.level?.id == id) return
        val level = LevelRegistry[id] ?: return
        editor.open(null)
        viewModelScope.launch {
            val saved = withContext(io) { circuits.load(id) }
            val firstVisit = withContext(io) { progress.markBriefSeen(id) }
            val catalog = _catalog.value
            val circuit = (saved ?: Circuit.forLevel(level.io)).sanitized(level.io, catalog.lookup(level.io))
            val chips = levelChips(level, catalog)
            val state = GameUiState(
                mode = EditorMode.Level(level),
                board = Board(circuit, level.io),
                catalog = catalog,
                chips = chips,
                manualInputs = List(level.inputs.size) { 0 },
                tray = ChipGroups.sections(chips, catalog),
                defaultGroup = TrayGroup.Builtin(level.chapter),
                bestCost = _progression.value.levels.firstOrNull { it.id == id }?.bestCost,
                showBrief = firstVisit,
                fitToken = ++fitToken,
                nextLevelId = LevelRegistry.next(id)?.id,
            )
            editor.open(state.copy(cost = cost(state, circuit)))
        }
    }

    /** Opens sandbox component [id] in the editor. */
    fun openComponent(id: String) {
        if (game.value?.sandbox?.id == id) return
        val catalog = _catalog.value
        val chip = catalog.custom(id) ?: return
        val pins = chip.board.io
        val board = chip.board.copy(circuit = chip.board.circuit.sanitized(pins, catalog.lookup(pins)))
        val chips = sandboxChips(id, catalog)
        val state = GameUiState(
            mode = EditorMode.Sandbox(id, chip.name, chip.group),
            board = board,
            catalog = catalog,
            chips = chips,
            manualInputs = List(board.io.inputs.size) { 0 },
            tray = ChipGroups.sections(chips, catalog),
            defaultGroup = TrayGroup.Builtin(Chapter.BASICS),
            showBrief = id == justCreated,
            fitToken = ++fitToken,
        )
        justCreated = null
        editor.open(state.copy(cost = cost(state, board.circuit)))
    }

    /** Adds an empty component named [name] in [group] and returns its id. */
    fun createComponent(name: String, group: String): String {
        val id = CustomChip.PREFIX + UUID.randomUUID()
        val chip = CustomChip(id, name.trim(), group.trim().ifEmpty { CustomChip.DEFAULT_GROUP }, Board.Empty)
        setCustoms(_catalog.value.customs + chip)
        justCreated = id
        return id
    }

    /** Deletes component [id]; any copies of it on other boards drop out as unknown chips. */
    fun deleteComponent(id: String) {
        if (game.value?.sandbox?.id == id) editor.open(null)
        setCustoms(_catalog.value.customs.filterNot { it.id == id })
    }

    private fun persist(state: GameUiState) {
        when (val mode = state.mode) {
            is EditorMode.Level -> circuits.save(mode.def.id, state.circuit)
            is EditorMode.Sandbox -> saveComponent(CustomChip(mode.id, mode.name, mode.group, state.board))
        }
    }

    private fun saveComponent(chip: CustomChip) {
        val old = _catalog.value.custom(chip.id) ?: return
        val remap = pinRemap(old, chip)
        val customs = _catalog.value.customs.map { c ->
            when {
                c.id == chip.id -> chip
                remap != null -> c.copy(board = c.board.copy(circuit = c.board.circuit.remapChipPins(chip.id, remap)))
                else -> c
            }
        }
        setCustoms(customs)
        if (remap != null) {
            viewModelScope.launch(io) { circuits.rewriteAll { it.remapChipPins(chip.id, remap) } }
        }
    }

    private fun Circuit.remapChipPins(chipId: String, remap: Pair<IntArray, IntArray>) =
        remapChipPins(chipId, remap.first, remap.second)

    private fun setCustoms(customs: List<CustomChip>, save: Boolean = true) {
        val catalog = ChipCatalog(customs)
        _catalog.value = catalog
        if (save) components.save(customs)
        _sandbox.value = SandboxUiState(
            customs.map { c ->
                ComponentCard(c.id, c.name, c.group, c.inputs.size, c.outputs.size, catalog[c.id]?.nandCost)
            },
        )
    }

    private fun levelChips(level: LevelDef, catalog: ChipCatalog): List<ChipSpec> =
        (LevelRegistry.allowedChips(level.id) + catalog.customs.map { it.id }).mapNotNull { catalog[it] }

    /** Unlocked built-ins, pins, and every component that does not contain [id]. */
    private fun sandboxChips(id: String, catalog: ChipCatalog): List<ChipSpec> {
        val customs = catalog.customs.filterNot { catalog.uses(it.id, id) }.map { it.id }
        return (LevelRegistry.unlockedChips(completed) + customs).mapNotNull { catalog[it] } + PinChips.all
    }

    private fun recordSolve(state: GameUiState) {
        val level = state.level ?: return
        viewModelScope.launch {
            withContext(io) {
                progress.record(level.id, state.cost)
                achievementsManager.checkExistingAchievements()
            }
            refreshProgression()
        }
    }

    private suspend fun refreshProgression() {
        val best = withContext(io) { progress.bestCosts() }
        completed = best.keys
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
        editor.update { s -> s.level?.let { s.copy(bestCost = best[it.id]) } ?: s }
    }
}
