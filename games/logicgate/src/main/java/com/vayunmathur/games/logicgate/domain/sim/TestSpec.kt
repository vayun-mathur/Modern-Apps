package com.vayunmathur.games.logicgate.domain.sim

/**
 * One row of a test: the level inputs to drive, the outputs expected (null = don't care), and a
 * plain-language [title] such as `1 OR 1 = 1`.
 */
data class Case(val inputs: List<Int>, val expected: List<Int?>, val title: String)

/** A program run on the finished CPU: 16 bytes of RAM, a tick budget, and the answer in `acc`. */
data class ProgramTest(val name: String, val ram: List<Int>, val maxTicks: Int, val expectAcc: Int)

/** How a level is checked. */
sealed interface TestSpec {
    /** Each case is settled from reset on its own. */
    data class Comb(val cases: List<Case>) : TestSpec

    /** Ticks run in order on one simulator, from reset. */
    data class Seq(val ticks: List<Case>) : TestSpec

    /**
     * Each program runs from reset until `halt`. With [harness] the circuit is a CPU whose
     * `instr`/`data` inputs are fed from a RAM model driven by its `pc`/`addr`/`dout`/`we`
     * outputs; without it the circuit must contain its own RAM16, which gets the program.
     */
    data class Programs(val programs: List<ProgramTest>, val harness: Boolean) : TestSpec
}
