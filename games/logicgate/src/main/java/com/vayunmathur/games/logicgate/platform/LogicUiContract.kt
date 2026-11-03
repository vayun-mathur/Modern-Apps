package com.vayunmathur.games.logicgate.platform

import com.vayunmathur.games.logicgate.domain.levels.Chapter
import com.vayunmathur.games.logicgate.domain.levels.LevelDef
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.NodeId
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
    val title: String,
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

/** What is selected on the canvas. */
sealed interface Selection {
    /** A gate. */
    data class OfNode(val id: NodeId) : Selection

    /** A wire. */
    data class OfWire(val wire: Wire) : Selection
}

/** Everything the editor draws for one level. */
data class GameUiState(
    val level: LevelDef,
    val circuit: Circuit,
    val chips: List<ChipSpec>,
    val manualInputs: List<Int>,
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
)

/** Editor callbacks. Every method has a no-op default so a preview needs only [Noop]. */
interface LogicActions {
    fun placeChip(chipId: String, at: Point) {}
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

    /** Ready-made instances. */
    companion object {
        val Noop: LogicActions = object : LogicActions {}
    }
}
