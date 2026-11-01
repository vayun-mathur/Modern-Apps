package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.domain.model.Behavior
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.sim.Case
import com.vayunmathur.games.logicgate.domain.sim.TestSpec
import kotlin.random.Random

/** Builds test tables from a reference function or a native chip. */
internal object TestGen {
    private const val EXHAUSTIVE_BITS = 10
    private const val RANDOM_CASES = 200

    fun chip(id: String): ChipSpec = requireNotNull(Chips[id]) { "unknown chip $id" }

    /**
     * Exhaustive when the inputs total at most [EXHAUSTIVE_BITS] bits; otherwise edge values for
     * every input (0, 1, 0x7F, 0x80, max) crossed together, plus seeded random vectors.
     */
    fun comb(inputs: List<Pin>, seed: Int, title: RowTitle, f: (IntArray) -> List<Int?>): TestSpec.Comb {
        val vectors = if (inputs.sumOf { it.width } <= EXHAUSTIVE_BITS) exhaustive(inputs) else sampled(inputs, seed)
        return TestSpec.Comb(
            vectors.map { v ->
                val expected = f(v.toIntArray())
                Case(v, expected, title(v, expected))
            },
        )
    }

    fun fromChip(id: String, title: RowTitle): TestSpec.Comb {
        val spec = chip(id)
        val eval = (spec.behavior as Behavior.Comb).eval
        return comb(spec.inputs, id.hashCode(), title) { eval(it).toList() }
    }

    /** Expected outputs come from running the native chip over [ticks]. */
    fun seqFromChip(id: String, title: RowTitle, ticks: List<List<Int>>): TestSpec.Seq {
        val seq = chip(id).behavior as Behavior.Seq
        var state = seq.init
        return TestSpec.Seq(
            ticks.map { t ->
                val ins = t.toIntArray()
                val out = seq.outputs(state, ins).toList()
                state = seq.next(state, ins)
                Case(t, out, title(t, out))
            },
        )
    }

    private fun exhaustive(inputs: List<Pin>): List<List<Int>> {
        val total = 1 shl inputs.sumOf { it.width }
        return (0 until total).map { code ->
            var rest = code
            inputs.asReversed().map { p ->
                val v = rest and p.mask
                rest = rest shr p.width
                v
            }.asReversed()
        }
    }

    private fun sampled(inputs: List<Pin>, seed: Int): List<List<Int>> {
        val edges = inputs.map { p -> listOf(0, 1, 0x7F, 0x80, p.mask).map { it and p.mask }.distinct() }
        val crossed = edges.fold(listOf(emptyList<Int>())) { acc, vals -> acc.flatMap { pre -> vals.map { pre + it } } }
        val rng = Random(seed)
        val random = List(RANDOM_CASES) { inputs.map { p -> rng.nextInt(p.mask + 1) } }
        return (crossed + random).distinct()
    }
}
