package com.vayunmathur.games.logicgate.domain

import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.ConnectCheck
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.Point
import com.vayunmathur.games.logicgate.domain.model.Wire
import com.vayunmathur.games.logicgate.domain.sim.Scoring
import com.vayunmathur.games.logicgate.domain.sim.TestSpec
import com.vayunmathur.games.logicgate.domain.sim.Verifier
import com.vayunmathur.games.logicgate.domain.sim.VerifyProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifierTest {

    private fun level(id: String) = requireNotNull(LevelRegistry[id])

    @Test
    fun anOrSubmittedForXorFailsOnTheBothHighRow() {
        val xor = level("xor")
        val board = Board(xor)
        with(board) { g(Chips.OR, input(0), input(1)).out() into output(0) }
        val result = Verifier.verify(xor.io, xor.tests, board.circuit)
        assertEquals(3, result.passed)
        assertEquals(3, result.firstFailureIndex)
        val failure = requireNotNull(result.firstFailure)
        assertEquals(listOf(1, 1), failure.inputs)
        assertEquals(listOf<Int?>(0), failure.expected)
        assertEquals(listOf(1), failure.actual)
        assertEquals("1 XOR 1 = 0", failure.title)
    }

    @Test
    fun everyRowOfEveryLevelHasATitle() {
        for (l in LevelRegistry.levels) {
            val result = Verifier.verify(l.io, l.tests, Circuit.forLevel(l.io))
            assertTrue(result.cases.all { it.title.isNotBlank() }, "${l.id} has an untitled row")
        }
    }

    @Test
    fun rowTitlesReadAsTheBehaviourBeingChecked() {
        fun titles(id: String) = Verifier.verify(level(id).io, level(id).tests, Circuit.forLevel(level(id).io)).cases.map { it.title }
        assertTrue("1 OR 1 = 1" in titles("or"))
        assertTrue("NOT 0 = 1" in titles("not"))
        assertTrue("1 + 1 + 1 = 1, carry 1" in titles("full_adder"))
        assertEquals("set → q=1", titles("sr_latch").first())
        assertTrue("JZ with z=1 → jump" in titles("decoder"))
        assertEquals("2 + 3 = 5", titles("computer").first())
    }

    @Test
    fun anEmptyBoardFailsWithoutCrashing() {
        for (l in LevelRegistry.levels) {
            val result = Verifier.verify(l.io, l.tests, Circuit.forLevel(l.io))
            assertFalse(result.solved, "${l.id} solved by an empty board")
        }
    }

    @Test
    fun smallInputsAreTestedExhaustivelyAndWideOnesAreSampled() {
        assertEquals(512, (level("add4").tests as TestSpec.Comb).cases.size)
        assertEquals(256, (level("zero8").tests as TestSpec.Comb).cases.size)
        val add8 = (level("add8").tests as TestSpec.Comb).cases.size
        assertTrue(add8 in 200..400, "add8 has $add8 cases")
    }

    @Test
    fun aDelayedLatchFailsTheTransparentLatchLevel() {
        val dLatch = level("d_latch")
        val board = Board(dLatch)
        with(board) { g(Chips.DFF, input(0)).out() into output(0) }
        assertFalse(Verifier.verify(dLatch.io, dLatch.tests, board.circuit).solved)
    }

    @Test
    fun theNativeCpuChipPassesTheCpuLevelHarness() {
        val cpu = level("cpu")
        val board = Board(cpu)
        with(board) {
            val chip = g(Chips.CPU, input(0), input(1))
            for (i in 0 until 6) chip.out(i) into output(i)
        }
        assertTrue(Verifier.verify(cpu.io, cpu.tests, board.circuit).solved)
    }

    @Test
    fun theComputerLevelReportsAMissingRam() {
        val computer = level("computer")
        val board = Board(computer)
        with(board) { g(Chips.CPU) }
        val result = Verifier.verify(computer.io, computer.tests, board.circuit)
        assertEquals(VerifyProblem.NO_RAM, result.problem)
        assertEquals(0, result.passed)
    }

    @Test
    fun probingASequentialRowReplaysEarlierTicks() {
        val bit = level("bit")
        val circuit = ReferenceCircuits.build("bit")
        val output = circuit.nodes.first { it is Gate && it.chipId == Chips.DFF }.id
        val values = Verifier.probe(bit.io, bit.tests, circuit, caseIndex = 1).values
        assertEquals(1, values.entries.first { it.key.node == output }.value)
    }

    @Test
    fun starsFollowPar() {
        assertEquals(0, Scoring.stars(solved = false, cost = 1, par = 10))
        assertEquals(3, Scoring.stars(solved = true, cost = 10, par = 10))
        assertEquals(2, Scoring.stars(solved = true, cost = 15, par = 10))
        assertEquals(1, Scoring.stars(solved = true, cost = 16, par = 10))
        assertEquals(3, Scoring.stars(solved = true, cost = 0, par = 0))
    }
}

class CircuitTest {
    private val level = requireNotNull(LevelRegistry["add4"])
    private val lookup = Chips.lookup(level.io)

    @Test
    fun wiresAreNormalisedOutputToInputAndReplaceTheOldWireOnAnInput() {
        val board = Board(level)
        val add = board.g(Chips.ADD4)
        val a = board.input(0)
        var c = requireNotNull(board.circuit.connect(add.inp(0), a, lookup))
        assertEquals(listOf(Wire(a, add.inp(0))), c.wires)
        c = requireNotNull(c.connect(board.input(1), add.inp(0), lookup))
        assertEquals(listOf(Wire(board.input(1), add.inp(0))), c.wires)
    }

    @Test
    fun mismatchedWidthsAndSameSidePinsAreRejected() {
        val board = Board(level)
        val add = board.g(Chips.ADD4)
        val c = board.circuit
        assertIs<ConnectCheck.WidthMismatch>(c.check(board.input(2), add.inp(0), lookup))
        assertIs<ConnectCheck.SameSide>(c.check(add.inp(0), add.inp(1), lookup))
        assertNull(c.connect(board.input(0), add.inp(2), lookup))
    }

    @Test
    fun removingAGateDropsItsWiresButLevelPinsStay() {
        val board = Board(level)
        val add = board.g(Chips.ADD4, board.input(0))
        val c = board.circuit.removeNode(add.id)
        assertTrue(c.wires.isEmpty())
        val input = c.nodes.first { it is LevelInput }
        assertEquals(c, c.removeNode(input.id))
    }

    @Test
    fun sanitizingDropsUnknownChipsAndRestoresMissingLevelPins() {
        val (withJunk, _) = Circuit().addGate("NOPE", Point(0f, 0f))
        val clean = withJunk.sanitized(level.io, lookup)
        assertTrue(clean.nodes.none { it is Gate })
        assertEquals(level.inputs.size + level.outputs.size, clean.nodes.size)
        assertEquals(clean.nodes.map { it.id }.toSet().size, clean.nodes.size)
    }
}
