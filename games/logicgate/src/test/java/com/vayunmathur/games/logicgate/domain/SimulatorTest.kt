package com.vayunmathur.games.logicgate.domain

import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.LevelIo
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.sim.Simulator
import com.vayunmathur.games.logicgate.domain.sim.Verifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatorTest {

    private fun out(c: Circuit, gate: Int, i: Int = 0) = PinRef(c.nodes[gate].id, PinSide.OUT, i)
    private fun inp(c: Circuit, gate: Int, i: Int) = PinRef(c.nodes[gate].id, PinSide.IN, i)

    @Test
    fun srLatchHoldsAcrossTicks() {
        val level = requireNotNull(LevelRegistry["sr_latch"])
        val sim = Simulator(ReferenceCircuits.build("sr_latch"), level.io)
        assertContentEquals(intArrayOf(1, 0), sim.step(intArrayOf(1, 0)).outputs)
        repeat(5) { assertContentEquals(intArrayOf(1, 0), sim.step(intArrayOf(0, 0)).outputs) }
        assertContentEquals(intArrayOf(0, 1), sim.step(intArrayOf(0, 1)).outputs)
        repeat(5) { assertContentEquals(intArrayOf(0, 1), sim.step(intArrayOf(0, 0)).outputs) }
    }

    @Test
    fun aNandFeedingItselfOscillates() {
        val io = LevelIo(emptyList(), listOf(Pin("o")))
        var c = Circuit.forLevel(io)
        val (withGate, id) = c.addGate(Chips.NAND, Point(0f, 0f))
        val lookup = Chips.lookup(io)
        val self = PinRef(id, PinSide.OUT, 0)
        c = withGate.connect(self, PinRef(id, PinSide.IN, 0), lookup)!!
        c = c.connect(self, PinRef(id, PinSide.IN, 1), lookup)!!
        assertEquals(setOf(id), Simulator(c, io).settle())
    }

    @Test
    fun aThreeInverterRingOscillatesButATwoInverterRingSettles() {
        val io = LevelIo(emptyList(), emptyList())
        val lookup = Chips.lookup(io)
        fun ring(n: Int): Circuit {
            var c = Circuit.forLevel(io)
            repeat(n) { c = c.addGate(Chips.NOT, Point(0f, 0f)).first }
            for (i in 0 until n) c = c.connect(out(c, i), inp(c, (i + 1) % n, 0), lookup)!!
            return c
        }
        assertEquals(3, Simulator(ring(3), io).settle().size)
        assertTrue(Simulator(ring(2), io).settle().isEmpty())
    }

    @Test
    fun unconnectedInputsReadZero() {
        val io = LevelIo(emptyList(), listOf(Pin("o")))
        val (c0, _) = Circuit.forLevel(io).addGate(Chips.NAND, Point(0f, 0f))
        val c = c0.connect(out(c0, 1), inp(c0, 0, 0), Chips.lookup(io))!!
        val sim = Simulator(c, io)
        sim.settle()
        assertContentEquals(intArrayOf(1), sim.outputs())
    }

    @Test
    fun registerProgramCounterAndRamTracesMatchTheirSpecs() {
        for (id in listOf("bit", "reg8", "pc4", "ram4")) {
            val level = requireNotNull(LevelRegistry[id])
            val result = Verifier.verify(level.io, level.tests, ReferenceCircuits.build(id))
            assertTrue(result.solved, "$id failed at ${result.firstFailure}")
        }
        val pc = requireNotNull(LevelRegistry["pc4"])
        val sim = Simulator(ReferenceCircuits.build("pc4"), pc.io)
        val counted = (0 until 18).map { sim.step(intArrayOf(1, 0, 0)).outputs[0] }
        assertEquals((0 until 18).map { it % 16 }, counted)
    }

    @Test
    fun theComputerRunsAllThreeProgramsToTheRightAnswer() {
        val level = requireNotNull(LevelRegistry["computer"])
        val result = Verifier.verify(level.io, level.tests, ReferenceCircuits.build("computer"))
        assertEquals(listOf(listOf(5, 1), listOf(0, 1), listOf(12, 1)), result.cases.map { it.actual })
    }

    @Test
    fun aPlayerBuiltCpuRunsTheSameProgramsInTheComputer() {
        val cpuLevel = requireNotNull(LevelRegistry["cpu"])
        val result = Verifier.verify(cpuLevel.io, cpuLevel.tests, ReferenceCircuits.build("cpu"))
        assertEquals(listOf(5, 0, 12), result.cases.map { it.actual?.get(4) })
        assertTrue(result.cases.all { it.actual?.get(5) == 1 })
    }
}
