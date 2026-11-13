package com.vayunmathur.games.logicgate.platform

import androidx.annotation.StringRes
import com.vayunmathur.games.logicgate.domain.levels.Chapter
import com.vayunmathur.games.logicgate.domain.levels.LevelDef
import com.vayunmathur.games.logicgate.domain.levels.TrayGroup
import com.vayunmathur.games.logicgate.domain.levels.TraySection
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.LevelIo
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinLookup
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.model.Wire
import com.vayunmathur.games.logicgate.domain.sim.Probe
import com.vayunmathur.games.logicgate.domain.sim.VerifyResult

/*
 * The UI contract between LogicViewModel and the screens: plain state plus an actions interface,
 * so every screen renders from a @Preview with LogicActions.Noop.
 */

/** One level card on the map. */
data class LevelCard(
    val id: String,
    val number: Int,
    @StringRes val title: Int,
    val chapter: Chapter,
    val unlocked: Boolean,
    val stars: Int,
    val bestCost: Int?,
    val par: Int,
) {
    val completed: Boolean get() = bestCost != null
}

/** The level map. */
data class ProgressionUiState(val levels: List<LevelCard> = emptyList()) {
    val solved: Int get() = levels.count { it.completed }
}

/** One component on the sandbox list. [cost] is null for a component that contains itself. */
data class ComponentCard(
    val id: String,
    val name: String,
    val group: String,
    val inputs: Int,
    val outputs: Int,
    val cost: Int?,
)

/** The sandbox list, components grouped by the player's group names. */
data class SandboxUiState(val components: List<ComponentCard> = emptyList()) {
    val groups: List<Pair<String, List<ComponentCard>>>
        get() = components.groupBy { it.group }.toSortedMap(String.CASE_INSENSITIVE_ORDER).toList()
}

/** What the editor is editing. */
sealed interface EditorMode {
    /** A Computer level, checked against its tests. */
    data class Level(val def: LevelDef) : EditorMode

    /** A sandbox component the player names, files under [group], and gives its own pins. */
    data class Sandbox(val id: String, val name: String, val group: String) : EditorMode
}

/** What is selected on the canvas. */
sealed interface Selection {
    /** A gate. */
    data class OfNode(val id: NodeId) : Selection

    /** A wire. */
    data class OfWire(val wire: Wire) : Selection
}

/** Everything the editor draws for one level or sandbox component. */
data class GameUiState(
    val mode: EditorMode,
    val board: Board,
    val catalog: ChipCatalog,
    val chips: List<ChipSpec>,
    val manualInputs: List<Int>,
    /** [chips] split into tray tabs, and the tab to open first. */
    val tray: List<TraySection> = emptyList(),
    val defaultGroup: TrayGroup? = null,
    val selection: Selection? = null,
    val verify: VerifyResult? = null,
    val probeCase: Int? = null,
    val live: Probe = Probe(emptyMap(), emptySet()),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val won: Boolean = false,
    val stars: Int = 0,
    val cost: Int = 0,
    val bestCost: Int? = null,
    val showBrief: Boolean = false,
    /** The tray chip whose info sheet is open. */
    val infoChip: ChipSpec? = null,
    /** Bumped when the canvas should frame the whole circuit, e.g. on opening a level. */
    val fitToken: Int = 0,
    val nextLevelId: String? = null,
) {
    val circuit: Circuit get() = board.circuit
    val io: LevelIo get() = board.io
    val level: LevelDef? get() = (mode as? EditorMode.Level)?.def
    val sandbox: EditorMode.Sandbox? get() = mode as? EditorMode.Sandbox
}

/** Editor callbacks. Every method has a no-op default so a preview needs only [Noop]. */
interface LogicActions {
    fun placeChip(chipId: String, at: Point) {}

    /**
     * Places [chipId] where [spot] says, asked against the board as it is when the placement
     * lands (not as the caller last saw it), so quick successive drops never stack.
     */
    fun placeChipNear(chipId: String, spot: (Circuit, PinLookup) -> Point) {}
    fun moveNode(id: NodeId, to: Point) {}
    fun connect(a: PinRef, b: PinRef) {}
    fun select(selection: Selection?) {}
    fun deleteSelection() {}
    fun deleteNode(id: NodeId) {}
    fun undo() {}
    fun redo() {}
    fun setInput(index: Int, value: Int) {}
    fun probe(caseIndex: Int?) {}
    fun showBrief(show: Boolean) {}
    fun showChipInfo(chip: ChipSpec?) {}
    fun dismissWin() {}
    fun renamePin(id: NodeId, name: String) {}
    fun renameComponent(name: String, group: String) {}

    /** Ready-made instances. */
    companion object {
        val Noop: LogicActions = object : LogicActions {}
    }
}
