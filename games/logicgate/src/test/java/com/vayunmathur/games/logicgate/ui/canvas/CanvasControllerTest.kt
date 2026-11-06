package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.platform.EditorMode
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LogicActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasControllerTest {
    private val level = requireNotNull(LevelRegistry["and"])
    private val lookup = Chips.lookup(level.io)
    private val trayTop = 1000f

    private class Recorder : LogicActions {
        val deleted = mutableListOf<NodeId>()
        val moved = mutableListOf<NodeId>()
        override fun deleteNode(id: NodeId) {
            deleted += id
        }
        override fun moveNode(id: NodeId, to: Point) {
            moved += id
        }
    }

    private fun setup(): Triple<CanvasController, Recorder, Circuit> {
        val (circuit, _) = Circuit.forLevel(level.io).addGate(Chips.NAND, Point(200f, 0f))
        val trash = TrashZone().apply { bounds = Rect(0f, trayTop, 2000f, 1400f) }
        val controller = CanvasController(CameraState(1f), 1f, trash)
        val actions = Recorder()
        val state = GameUiState(EditorMode.Level(level), Board(circuit, level.io), ChipCatalog.Builtin, emptyList(), listOf(0, 0))
        controller.bind(state, actions, CircuitLayout(circuit, lookup, emptyMap()), lookup)
        return Triple(controller, actions, circuit)
    }

    @Test
    fun aGateDroppedOnTheTrayIsDeleted() {
        val (controller, actions, circuit) = setup()
        val gate = circuit.nodes.last().id
        controller.startMove(gate)
        controller.dragMove(Offset(0f, 900f), Offset(300f, trayTop + 50f))
        controller.endMove(Offset(300f, trayTop + 50f))
        assertEquals(listOf(gate), actions.deleted)
        assertTrue(actions.moved.isEmpty())
    }

    @Test
    fun aGateDroppedOnTheBoardJustMoves() {
        val (controller, actions, circuit) = setup()
        val gate = circuit.nodes.last().id
        controller.startMove(gate)
        controller.dragMove(Offset(100f, 100f), Offset(400f, 300f))
        controller.endMove(Offset(400f, 300f))
        assertTrue(actions.deleted.isEmpty())
        assertEquals(listOf(gate), actions.moved)
    }

    @Test
    fun aChipPulledFromTheTrayShowsTheTrashUntilItLeavesTheBottomArea() {
        val trash = TrashZone().apply { bounds = Rect(0f, trayTop, 2000f, 1400f) }
        assertFalse(trash.carry(Offset(300f, trayTop + 50f)), "picking up is not re-entering")
        assertTrue(trash.armed)
        assertTrue(trash.hovering)
        trash.carry(Offset(300f, 400f))
        assertTrue(trash.armed)
        assertFalse(trash.hovering)
        assertTrue(trash.carry(Offset(300f, trayTop + 10f)), "coming back over the tray")
        trash.carry(null)
        assertFalse(trash.armed)
        assertFalse(trash.hovering)
    }

    @Test
    fun levelPinsCannotBeThrownAway() {
        val (controller, actions, circuit) = setup()
        val input = circuit.nodes.first { it is LevelInput }.id
        controller.startMove(input)
        controller.dragMove(Offset(0f, 900f), Offset(300f, trayTop + 50f))
        controller.endMove(Offset(300f, trayTop + 50f))
        assertTrue(actions.deleted.isEmpty())
        assertFalse(actions.moved.isEmpty())
    }
}
