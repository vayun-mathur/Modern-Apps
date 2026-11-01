package com.vayunmathur.games.logicgate.domain.sim

import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.Gate

/** Circuit cost and star rating. */
object Scoring {

    fun cost(circuit: Circuit, nandCost: (String) -> Int?): Int =
        circuit.nodes.sumOf { (it as? Gate)?.let { g -> nandCost(g.chipId) } ?: 0 }

    /** ★ solved, ★★ within 1.5× par, ★★★ at or under par; 0 when unsolved. */
    fun stars(solved: Boolean, cost: Int, par: Int): Int = when {
        !solved -> 0
        cost <= par -> 3
        cost * 2 <= par * 3 -> 2
        else -> 1
    }
}
