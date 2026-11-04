package com.vayunmathur.games.logicgate.domain.levels

import androidx.annotation.StringRes
import com.vayunmathur.games.logicgate.domain.model.LevelIo
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.sim.TestSpec

/** The five chapters, in play order. */
enum class Chapter { BASICS, ROUTING, ARITHMETIC, MEMORY, CPU }

/**
 * One puzzle. [gifts] are chips handed over for this level onwards without being earned;
 * [unlocks] are chips earned by solving it. [par] is the NAND cost of the reference solution.
 */
data class LevelDef(
    val id: String,
    val chapter: Chapter,
    @StringRes val title: Int,
    @StringRes val brief: Int,
    @StringRes val hint: Int,
    val inputs: List<Pin>,
    val outputs: List<Pin>,
    val tests: TestSpec,
    val par: Int,
    val gifts: List<String> = emptyList(),
    val unlocks: List<String> = emptyList(),
) {
    val io: LevelIo get() = LevelIo(inputs, outputs)
    val isSequential: Boolean get() = tests !is TestSpec.Comb
}
