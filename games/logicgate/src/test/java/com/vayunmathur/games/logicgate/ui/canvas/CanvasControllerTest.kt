package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.platform.EditorMode
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LogicActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        controller.startMove(gate, Offset(240f, 28f))
        controller.dragMove(Offset(300f, trayTop + 50f))
        controller.endMove(Offset(300f, trayTop + 50f))
        assertEquals(listOf(gate), actions.deleted)
        assertTrue(actions.moved.isEmpty())
    }

    @Test
    fun aGateDroppedOnTheBoardJustMoves() {
        val (controller, actions, circuit) = setup()
        val gate = circuit.nodes.last().id
        controller.startMove(gate, Offset(240f, 28f))
        controller.dragMove(Offset(400f, 300f))
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
    fun innerPinRadiusIsCappedToAFractionOfTheGateWhenZoomedOut() {
        val (controller, _, _) = setup()
        assertEquals(14f, controller.innerPinRadius(), 0.01f, "14 dp at 1x")
        val camera = CameraState(1f)
        camera.zoom(Offset.Zero, 0.01f) // clamps to the minimum zoom
        val zoomedOut = CanvasController(camera, 1f, TrashZone())
        assertEquals(GateGeometry.GATE_WIDTH * 0.3f, zoomedOut.innerPinRadius(), 0.01f)
    }

    @Test
    fun atMinimumZoomTheMiddleOfAGatePicksItUp() {
        val (circuit, _) = Circuit.forLevel(level.io).addGate(Chips.NAND, Point(200f, 0f))
        val gate = circuit.nodes.last().id
        val camera = CameraState(1f).apply { zoom(Offset.Zero, 0.01f) }
        val controller = CanvasController(camera, 1f, TrashZone())
        val state = GameUiState(EditorMode.Level(level), Board(circuit, level.io), ChipCatalog.Builtin, emptyList(), listOf(0, 0))
        val layout = CircuitLayout(circuit, lookup, emptyMap())
        controller.bind(state, Recorder(), layout, lookup)
        val center = camera.camera.toScreen(layout.rects.getValue(gate).center)
        assertEquals(Hit.OnNode(gate), controller.hit(center))
        // The pins themselves are still reachable.
        val pin = layout.anchors.keys.first { it.node == gate }
        assertIs<Hit.OnPin>(controller.hit(camera.camera.toScreen(layout.anchors.getValue(pin))))
    }

    @Test
    fun aCarriedGateStaysUnderTheFingerWhenTheBoardPansBeneathIt() {
        val (controller, _, circuit) = setup()
        val gate = circuit.nodes.last().id
        val finger = Offset(240f, 28f)
        controller.startMove(gate, finger)
        controller.pan(Offset(-100f, 0f)) // e.g. a second finger panning
        controller.dragMove(finger)
        val moving = controller.drag as Drag.Moving
        assertEquals(Offset(100f, 0f), moving.delta)
    }

    @Test
    fun holdingAWireNearAnEdgeAutoPansAndKeepsTheWireOnTheFinger() {
        val (controller, _, circuit) = setup()
        val camera = CameraState(1f).apply { viewport = Size(1000f, 800f) }
        val trash = TrashZone().apply { bounds = Rect(0f, trayTop, 2000f, 1400f) }
        val c = CanvasController(camera, 1f, trash)
        val state = GameUiState(EditorMode.Level(level), Board(circuit, level.io), ChipCatalog.Builtin, emptyList(), listOf(0, 0))
        c.bind(state, Recorder(), CircuitLayout(circuit, lookup, emptyMap()), lookup)
        val out = PinRef(circuit.nodes.last().id, PinSide.OUT, 0)
        c.startWire(out, Offset(280f, 28f))
        assertFalse(c.autoPan(Offset(500f, 400f), 16f), "no pan in the middle")
        val before = camera.camera.offset
        val edge = Offset(995f, 400f)
        assertTrue(c.autoPan(edge, 16f), "pans near the right edge")
        assertTrue(camera.camera.offset.x < before.x, "content moves left to reveal the right")
        assertEquals(before.y, camera.camera.offset.y)
        assertEquals(camera.camera.toWorld(edge), (c.drag as Drag.Wiring).world)
        assertFalse(controller.autoPan(edge, 16f), "nothing carried, nothing to pan")
    }

    @Test
    fun autoPanVelocityRampsUpOnlyInsideTheEdgeBand() {
        val viewport = Size(1000f, 800f)
        assertEquals(Offset.Zero, AutoPan.velocity(Offset(500f, 400f), viewport, 40f, 1f))
        assertEquals(Offset(-1f, 0f), AutoPan.velocity(Offset(1000f, 400f), viewport, 40f, 1f))
        assertEquals(Offset(0.5f, 0f), AutoPan.velocity(Offset(20f, 400f), viewport, 40f, 1f))
        assertEquals(Offset(0f, 0.5f), AutoPan.velocity(Offset(500f, 20f), viewport, 40f, 1f))
        assertEquals(Offset.Zero, AutoPan.velocity(Offset(500f, 900f), viewport, 40f, 1f), "below the board (over the tray)")
        assertEquals(Offset.Zero, AutoPan.velocity(Offset(5f, 5f), Size.Zero, 40f, 1f))
    }

    @Test
    fun levelPinsCannotBeThrownAway() {
        val (controller, actions, circuit) = setup()
        val input = circuit.nodes.first { it is LevelInput }.id
        controller.startMove(input, Offset(20f, 20f))
        controller.dragMove(Offset(300f, trayTop + 50f))
        controller.endMove(Offset(300f, trayTop + 50f))
        assertTrue(actions.deleted.isEmpty())
        assertFalse(actions.moved.isEmpty())
    }
}
