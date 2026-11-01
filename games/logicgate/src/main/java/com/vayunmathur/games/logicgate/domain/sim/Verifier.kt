package com.vayunmathur.games.logicgate.domain.sim

import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelIo
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinRef

/** The outcome of one test row. [actual] is null when the row never ran. */
data class CaseResult(
    val title: String,
    val inputs: List<Int>,
    val expected: List<Int?>,
    val actual: List<Int>?,
    val ok: Boolean,
)

/** Why a level could not even be run. */
enum class VerifyProblem { NO_RAM }

/** Every row's outcome plus anything that stopped the circuit from settling. */
data class VerifyResult(
    val cases: List<CaseResult>,
    val oscillating: Set<NodeId> = emptySet(),
    val problem: VerifyProblem? = null,
) {
    val total: Int get() = cases.size
    val passed: Int get() = cases.count { it.ok }
    val solved: Boolean get() = total > 0 && passed == total
    val firstFailureIndex: Int? get() = cases.indexOfFirst { !it.ok }.takeIf { it >= 0 }
    val firstFailure: CaseResult? get() = firstFailureIndex?.let { cases[it] }
}

/** Live wire values for one moment of a run, for the canvas. */
data class Probe(val values: Map<PinRef, Int>, val oscillating: Set<NodeId>)

/** Runs a level's tests against a player's circuit. */
object Verifier {

    fun verify(
        io: LevelIo,
        tests: TestSpec,
        circuit: Circuit,
        catalog: (String) -> ChipSpec? = Chips::get,
    ): VerifyResult {
        val sim = Simulator(circuit, io, catalog)
        return when (tests) {
            is TestSpec.Comb -> verifyComb(sim, tests)
            is TestSpec.Seq -> verifySeq(sim, tests)
            is TestSpec.Programs -> verifyPrograms(sim, io, tests, circuit)
        }
    }

    /** Wire values with the level inputs held at [inputs], from reset. */
    fun manual(io: LevelIo, circuit: Circuit, inputs: List<Int>): Probe {
        val sim = Simulator(circuit, io)
        sim.setInputs(inputs.toIntArray())
        val osc = sim.settle()
        return Probe(sim.pinValues(), osc)
    }

    /** Wire values at test row [caseIndex], replaying earlier ticks for sequential levels. */
    fun probe(io: LevelIo, tests: TestSpec, circuit: Circuit, caseIndex: Int): Probe {
        val sim = Simulator(circuit, io)
        val osc: Set<NodeId> = when (tests) {
            is TestSpec.Comb -> {
                sim.setInputs(tests.cases[caseIndex].inputs.toIntArray())
                sim.settle()
            }
            is TestSpec.Seq -> {
                for (t in 0 until caseIndex) sim.step(tests.ticks[t].inputs.toIntArray())
                sim.setInputs(tests.ticks[caseIndex].inputs.toIntArray())
                sim.settle()
            }
            is TestSpec.Programs -> run(sim, io, tests.programs[caseIndex], tests.harness).oscillating
        }
        return Probe(sim.pinValues(), osc)
    }

    private fun matches(expected: List<Int?>, actual: IntArray): Boolean =
        expected.indices.all { k -> expected[k] == null || expected[k] == actual.getOrNull(k) }

    private fun verifyComb(sim: Simulator, tests: TestSpec.Comb): VerifyResult {
        val osc = LinkedHashSet<NodeId>()
        val results = tests.cases.map { c ->
            sim.reset()
            sim.setInputs(c.inputs.toIntArray())
            val o = sim.settle()
            osc += o
            val out = sim.outputs()
            CaseResult(c.title, c.inputs, c.expected, out.toList(), o.isEmpty() && matches(c.expected, out))
        }
        return VerifyResult(results, osc)
    }

    private fun verifySeq(sim: Simulator, tests: TestSpec.Seq): VerifyResult {
        val osc = LinkedHashSet<NodeId>()
        val results = tests.ticks.map { c ->
            val step = sim.step(c.inputs.toIntArray())
            osc += step.oscillating
            val ok = step.oscillating.isEmpty() && matches(c.expected, step.outputs)
            CaseResult(c.title, c.inputs, c.expected, step.outputs.toList(), ok)
        }
        return VerifyResult(results, osc)
    }

    private fun verifyPrograms(sim: Simulator, io: LevelIo, tests: TestSpec.Programs, circuit: Circuit): VerifyResult {
        val accIdx = io.outputs.indexOfFirst { it.name == "acc" }
        val haltIdx = io.outputs.indexOfFirst { it.name == "halt" }
        val noRam = !tests.harness && circuit.nodes.none { it is Gate && it.chipId == Chips.RAM16 }
        val osc = LinkedHashSet<NodeId>()
        val results = tests.programs.map { p ->
            val expected = io.outputs.indices.map { k ->
                when (k) {
                    accIdx -> p.expectAcc
                    haltIdx -> 1
                    else -> null
                }
            }
            if (noRam) return@map CaseResult(p.name, emptyList(), expected, null, false)
            val run = run(sim, io, p, tests.harness)
            osc += run.oscillating
            val ok = run.oscillating.isEmpty() && matches(expected, run.outputs)
            CaseResult(p.name, emptyList(), expected, run.outputs.toList(), ok)
        }
        return VerifyResult(results, osc, if (noRam) VerifyProblem.NO_RAM else null)
    }

    /** Runs [program] until halt or the tick budget, leaving [sim] settled on the last tick. */
    private fun run(sim: Simulator, io: LevelIo, program: ProgramTest, harness: Boolean): Simulator.Step {
        sim.reset()
        val haltIdx = io.outputs.indexOfFirst { it.name == "halt" }
        val ram = IntArray(RAM_SIZE) { program.ram.getOrElse(it) { 0 } }
        if (!harness) sim.preload(Chips.RAM16, ram)
        val bus = if (harness) Harness(io, ram) else null
        var last = Simulator.Step(IntArray(io.outputs.size), emptySet())
        for (t in 0 until program.maxTicks) {
            val osc = bus?.settle(sim) ?: sim.settle()
            last = Simulator.Step(sim.outputs(), osc)
            if (osc.isNotEmpty() || last.outputs.getOrNull(haltIdx) == 1) break
            bus?.write(last.outputs)
            sim.clock()
        }
        return last
    }

    /** The RAM model wired to a bare CPU's pins for the CPU level. */
    private class Harness(io: LevelIo, val ram: IntArray) {
        private val pc = io.outputs.indexOfFirst { it.name == "pc" }
        private val addr = io.outputs.indexOfFirst { it.name == "addr" }
        private val dout = io.outputs.indexOfFirst { it.name == "dout" }
        private val we = io.outputs.indexOfFirst { it.name == "we" }

        fun settle(sim: Simulator): Set<NodeId> {
            var inputs = intArrayOf(-1, -1)
            var osc: Set<NodeId> = emptySet()
            repeat(HARNESS_PASSES) {
                val out = sim.outputs()
                val next = intArrayOf(ram[out[pc] and 0xF], ram[out[addr] and 0xF])
                if (next.contentEquals(inputs)) return osc
                inputs = next
                sim.setInputs(inputs)
                osc = sim.settle()
            }
            return osc
        }

        fun write(outputs: IntArray) {
            if (outputs[we] == 1) ram[outputs[addr] and 0xF] = outputs[dout]
        }
    }

    private const val RAM_SIZE = 16
    private const val HARNESS_PASSES = 8
}
