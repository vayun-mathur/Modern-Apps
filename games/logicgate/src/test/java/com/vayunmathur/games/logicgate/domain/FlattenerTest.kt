package com.vayunmathur.games.logicgate.domain

import com.vayunmathur.games.logicgate.domain.model.Board
import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.CustomChip
import com.vayunmathur.games.logicgate.domain.model.LevelIo
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.sim.Simulator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlattenerTest {

    /** Builds a board edit by edit; pins first, then gates, then wires by node id. */
    private class Build(var board: Board, val catalog: ChipCatalog = ChipCatalog.Builtin) {
        fun input(y: Float = 0f, width: Int = 1): NodeId = pin(true, y, width)
        fun output(y: Float = 0f, width: Int = 1): NodeId = pin(false, y, width)

        private fun pin(input: Boolean, y: Float, width: Int): NodeId {
            val id = NodeId(board.circuit.nextId)
            board = board.addPin(input, width, Point(if (input) 0f else 400f, y))
            return id
        }

        fun gate(chipId: String): NodeId {
            val (c, id) = board.circuit.addGate(chipId, Point(200f, 0f))
            board = board.copy(circuit = c)
            return id
        }

        fun wire(from: NodeId, fromPin: Int, to: NodeId, toPin: Int) {
            val c = board.circuit.connect(
                PinRef(from, PinSide.OUT, fromPin),
                PinRef(to, PinSide.IN, toPin),
                catalog.lookup(board.io),
            )
            board = board.copy(circuit = checkNotNull(c) { "bad wire $from.$fromPin -> $to.$toPin" })
        }

        fun chip(id: String) = CustomChip(id, id, "Test", board)
    }

    private fun xorChip(): CustomChip = Build(Board.Empty).run {
        val a = input(0f)
        val b = input(96f)
        val o = output(48f)
        val n1 = gate(Chips.NAND)
        val n2 = gate(Chips.NAND)
        val n3 = gate(Chips.NAND)
        val n4 = gate(Chips.NAND)
        wire(a, 0, n1, 0); wire(b, 0, n1, 1)
        wire(a, 0, n2, 0); wire(n1, 0, n2, 1)
        wire(b, 0, n3, 0); wire(n1, 0, n3, 1)
        wire(n2, 0, n4, 0); wire(n3, 0, n4, 1)
        wire(n4, 0, o, 0)
        chip("custom:xor")
    }

    /** A level-style board with [ins] 1-bit inputs and one output around a single [chipId] gate. */
    private fun around(chipId: String, ins: Int, catalog: ChipCatalog): Pair<Board, NodeId> = Build(Board.Empty, catalog).run {
        val inputs = (0 until ins).map { input(it * 96f) }
        val o = output()
        val g = gate(chipId)
        inputs.forEachIndexed { i, n -> wire(n, 0, g, i) }
        wire(g, 0, o, 0)
        board to g
    }

    private fun settle(board: Board, catalog: ChipCatalog, vararg inputs: Int): Int {
        val sim = Simulator(board.circuit, board.io, catalog::get)
        sim.setInputs(inputs)
        sim.settle()
        return sim.outputs()[0]
    }

    @Test
    fun aComponentBuiltFromNandsMatchesTheNativeXor() {
        val catalog = ChipCatalog(listOf(xorChip()))
        val (board, g) = around("custom:xor", 2, catalog)
        for (a in 0..1) for (b in 0..1) assertEquals(a xor b, settle(board, catalog, a, b), "$a xor $b")
        assertEquals(4, catalog["custom:xor"]?.nandCost)

        val sim = Simulator(board.circuit, board.io, catalog::get)
        sim.setInputs(intArrayOf(1, 0))
        sim.settle()
        assertEquals(1, sim.pinValues()[PinRef(g, PinSide.OUT, 0)])
    }

    @Test
    fun componentsNestAndCostsAddUp() {
        val xor = xorChip()
        val xnor = Build(Board.Empty, ChipCatalog(listOf(xor))).run {
            val a = input(0f)
            val b = input(96f)
            val o = output()
            val x = gate(xor.id)
            val n = gate(Chips.NOT)
            wire(a, 0, x, 0); wire(b, 0, x, 1)
            wire(x, 0, n, 0); wire(n, 0, o, 0)
            chip("custom:xnor")
        }
        val catalog = ChipCatalog(listOf(xor, xnor))
        val (board, _) = around(xnor.id, 2, catalog)
        for (a in 0..1) for (b in 0..1) assertEquals(1 - (a xor b), settle(board, catalog, a, b))
        assertEquals(5, catalog[xnor.id]?.nandCost)
    }

    @Test
    fun aPassThroughComponentForwardsItsInputAndAWireOnlyLoopReadsZero() {
        val buf = Build(Board.Empty).run {
            val i = input()
            val o = output()
            wire(i, 0, o, 0)
            chip("custom:buf")
        }
        val catalog = ChipCatalog(listOf(buf))
        val (board, _) = around(buf.id, 1, catalog)
        assertEquals(1, settle(board, catalog, 1))
        assertEquals(0, settle(board, catalog, 0))

        val loop = Build(Board.Empty, catalog).run {
            val o = output()
            val g = gate(buf.id)
            wire(g, 0, g, 0)
            wire(g, 0, o, 0)
            board
        }
        assertEquals(0, settle(loop, catalog))
    }

    @Test
    fun aLatchInsideAComponentHoldsAcrossTicks() {
        val latch = Build(Board.Empty).run {
            val sBar = input(0f)
            val rBar = input(96f)
            val q = output()
            val top = gate(Chips.NAND)
            val bottom = gate(Chips.NAND)
            wire(sBar, 0, top, 0); wire(bottom, 0, top, 1)
            wire(rBar, 0, bottom, 1); wire(top, 0, bottom, 0)
            wire(top, 0, q, 0)
            chip("custom:latch")
        }
        val catalog = ChipCatalog(listOf(latch))
        val (board, _) = around(latch.id, 2, catalog)
        val sim = Simulator(board.circuit, board.io, catalog::get)
        assertContentEquals(intArrayOf(1), sim.step(intArrayOf(0, 1)).outputs)
        repeat(3) { assertContentEquals(intArrayOf(1), sim.step(intArrayOf(1, 1)).outputs) }
        assertContentEquals(intArrayOf(0), sim.step(intArrayOf(1, 0)).outputs)
        repeat(3) { assertContentEquals(intArrayOf(0), sim.step(intArrayOf(1, 1)).outputs) }
    }

    @Test
    fun feedbackThroughARegisterComponentToggles() {
        val reg = Build(Board.Empty).run {
            val d = input()
            val q = output()
            val ff = gate(Chips.DFF)
            wire(d, 0, ff, 0); wire(ff, 0, q, 0)
            chip("custom:reg")
        }
        val catalog = ChipCatalog(listOf(reg))
        val board = Build(Board.Empty, catalog).run {
            val o = output()
            val r = gate(reg.id)
            val n = gate(Chips.NOT)
            wire(r, 0, n, 0); wire(n, 0, r, 0); wire(r, 0, o, 0)
            board
        }
        val sim = Simulator(board.circuit, board.io, catalog::get)
        assertEquals(listOf(0, 1, 0, 1), (0 until 4).map { sim.step(IntArray(0)).outputs[0] })
    }

    @Test
    fun oscillationInsideAComponentIsReportedOnTheTopLevelGate() {
        val ring = Build(Board.Empty).run {
            val o = output()
            val n = gate(Chips.NAND)
            wire(n, 0, n, 0); wire(n, 0, n, 1); wire(n, 0, o, 0)
            chip("custom:ring")
        }
        val catalog = ChipCatalog(listOf(ring))
        val io = LevelIo(emptyList(), listOf(Pin("o")))
        val (c, g) = Circuit.forLevel(io).addGate(ring.id, Point(0f, 0f))
        assertEquals(setOf(g), Simulator(c, io, catalog::get).settle())
    }

    @Test
    fun aComponentThatContainsItselfResolvesToNothing() {
        val self = CustomChip(
            "custom:self", "self", "Test",
            Board(Circuit().addGate("custom:self", Point(0f, 0f)).first, LevelIo(emptyList(), emptyList())),
        )
        val catalog = ChipCatalog(listOf(self))
        assertNull(catalog[self.id])
        assertTrue(catalog.uses(self.id, self.id))
    }
}
