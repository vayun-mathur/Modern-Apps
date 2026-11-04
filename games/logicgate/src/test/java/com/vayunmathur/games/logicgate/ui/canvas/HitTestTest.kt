package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HitTestTest {
    private val level = requireNotNull(LevelRegistry["and"])
    private val lookup = Chips.lookup(level.io)

    /** An AND level board with one NAND whose output is wired to the level output. */
    private fun board(): Pair<CircuitLayout, PinRef> {
        val (c0, nand) = Circuit.forLevel(level.io).addGate(Chips.NAND, Point(200f, 0f))
        val output = c0.nodes.first { it is com.vayunmathur.games.logicgate.domain.model.LevelOutput }.id
        val c = requireNotNull(c0.connect(PinRef(nand, PinSide.OUT, 0), PinRef(output, PinSide.IN, 0), lookup))
        return CircuitLayout(c, lookup, emptyMap()) to PinRef(nand, PinSide.OUT, 0)
    }

    private fun hit(layout: CircuitLayout, at: Offset) = HitTest.hit(layout, at, 24f, 14f, 12f)

    @Test
    fun aTouchJustOutsideAGateEdgeGrabsThePin() {
        val (layout, out) = board()
        val anchor = requireNotNull(layout.anchors[out])
        assertEquals(Hit.OnPin(out), hit(layout, anchor + Offset(18f, 0f)))
    }

    @Test
    fun theMiddleOfAGateIsItsBody() {
        val (layout, out) = board()
        val rect = requireNotNull(layout.rects[out.node])
        assertEquals(Hit.OnNode(out.node), hit(layout, rect.center))
    }

    @Test
    fun aPinStillWinsOverTheBodyRightNextToIt() {
        val (layout, out) = board()
        val anchor = requireNotNull(layout.anchors[out])
        assertEquals(Hit.OnPin(out), hit(layout, anchor - Offset(8f, 0f)))
    }

    @Test
    fun aTouchOnAWireMidwaySelectsTheWire() {
        val (layout, out) = board()
        val wire = layout.circuit.wires.single()
        val a = requireNotNull(layout.anchors[wire.from])
        val b = requireNotNull(layout.anchors[wire.to])
        val mid = GateGeometry.samples(a, b, 2)[1]
        assertIs<Hit.OnWire>(hit(layout, mid + Offset(0f, 6f)))
        assertEquals(Hit.Empty, hit(layout, mid + Offset(0f, 200f)))
        assertTrue(out.node in layout.rects)
    }

    @Test
    fun placementAvoidsExistingNodes() {
        val (layout, out) = board()
        val occupied = requireNotNull(layout.rects[out.node])
        val spot = layout.freeSpot(Chips.NAND, occupied.center, lookup)
        assertTrue(Offset(spot.x, spot.y) != occupied.topLeft)
        assertEquals(0f, spot.x % GateGeometry.GRID)
    }
}
