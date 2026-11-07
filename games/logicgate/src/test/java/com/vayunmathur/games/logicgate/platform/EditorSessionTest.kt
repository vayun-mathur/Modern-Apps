package com.vayunmathur.games.logicgate.platform

import com.vayunmathur.games.logicgate.domain.ReferenceCircuits
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EditorSessionTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    @AfterTest fun tearDown() { scope.cancel() }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun levelState(solvedCircuit: Boolean = false): GameUiState {
        val level = requireNotNull(LevelRegistry["not"])
        val circuit = if (solvedCircuit) ReferenceCircuits.build("not") else Circuit.forLevel(level.io)
        val chips = LevelRegistry.allowedChips(level.id).mapNotNull { ChipCatalog.Builtin[it] }
        return GameUiState(
            mode = EditorMode.Level(level),
            board = Board(circuit, level.io),
            catalog = ChipCatalog.Builtin,
            chips = chips,
            manualInputs = List(level.inputs.size) { 0 },
        )
    }

    private fun session(persisted: MutableList<GameUiState> = mutableListOf(), solved: MutableList<GameUiState> = mutableListOf()) =
        EditorSession(scope, Dispatchers.Unconfined, { persisted += it }, { solved += it })

    @Test fun `opening a solved level verifies and announces solve`() {
        val solvedCalls = mutableListOf<GameUiState>()
        val session = session(solved = solvedCalls)
        session.open(levelState(solvedCircuit = true))
        await("verify solved") { session.state.value?.verify?.solved == true }
        assertTrue(session.state.value!!.verify!!.solved)
        // open() verifies without announcing; the result and stars still land in state
        assertTrue(session.state.value!!.verify!!.total > 0)
        assertTrue(session.state.value!!.stars > 0)
        session.open(null)
        assertNull(session.state.value)
    }

    @Test fun `place move undo redo and delete flow with persist`() {
        val persisted = mutableListOf<GameUiState>()
        val session = session(persisted = persisted)
        session.open(levelState())
        assertFalse(session.state.value!!.canUndo)
        session.placeChip("unknown-chip", Point(0f, 0f)) // ignored
        assertFalse(session.state.value!!.canUndo)
        session.placeChip(Chips.NAND, Point(100f, 100f))
        assertTrue(session.state.value!!.canUndo)
        val gateId = session.state.value!!.circuit.nodes.last().id
        session.moveNode(gateId, Point(200f, 120f))
        session.select(Selection.OfNode(gateId))
        assertEquals(gateId, (session.state.value!!.selection as Selection.OfNode).id)
        session.undo()
        session.redo()
        assertTrue(persisted.isNotEmpty())
        session.deleteSelection()
        assertNull(session.state.value!!.selection)
        session.undo() // gate returns
        assertTrue(session.state.value!!.circuit.nodes.any { it.id == gateId })
    }

    @Test fun `inputs probe brief info and win flags update state`() {
        val session = session()
        session.open(levelState())
        session.setInput(0, 1)
        assertEquals(listOf(1), session.state.value!!.manualInputs)
        assertNull(session.state.value!!.probeCase)
        session.showBrief(true); assertTrue(session.state.value!!.showBrief)
        session.showBrief(false); assertFalse(session.state.value!!.showBrief)
        val chip = session.state.value!!.chips.first()
        session.showChipInfo(chip); assertEquals(chip, session.state.value!!.infoChip)
        session.showChipInfo(null); assertNull(session.state.value!!.infoChip)
        session.dismissWin(); assertFalse(session.state.value!!.won)
        session.update { it.copy(fitToken = 9) }
        assertEquals(9, session.state.value!!.fitToken)
    }

    @Test fun `sandbox pins rename and delete`() {
        val session = session()
        val chips = listOf(ChipCatalog.Builtin[Chips.NAND]!!)
        val state = GameUiState(
            mode = EditorMode.Sandbox("custom:1", "My Chip", "Group"),
            board = Board.Empty,
            catalog = ChipCatalog.Builtin,
            chips = chips,
            manualInputs = emptyList(),
        )
        session.open(state)
        session.placeChip("pin:in:1", Point(0f, 0f))
        val pinId = session.state.value!!.board.circuit.nodes.last().id
        session.renamePin(pinId, "clock")
        session.renameComponent("  Renamed ", " New Group ")
        assertEquals("Renamed", session.state.value!!.sandbox?.name)
        assertEquals("New Group", session.state.value!!.sandbox?.group)
        session.renameComponent("", "") // blanks keep old names
        assertEquals("Renamed", session.state.value!!.sandbox?.name)
        session.deleteNode(pinId)
        // Level-mode rename is ignored
        session.open(levelState())
        session.renamePin(pinId, "nope")
        assertNotNull(session.state.value)
    }

    @Test fun `edits on empty session are ignored`() {
        val session = session()
        session.placeChip(Chips.NAND, Point(0f, 0f))
        session.undo(); session.redo(); session.deleteSelection()
        session.setInput(0, 1)
        assertNull(session.state.value)
        val noop = LogicActions.Noop
        noop.placeChip(Chips.NAND, Point(0f, 0f)); noop.undo(); noop.dismissWin()
    }
}
