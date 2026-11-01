package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.domain.model.Isa
import com.vayunmathur.games.logicgate.domain.model.formatValue

/** Builds a test row's title from its inputs and expected outputs. */
typealias RowTitle = (inputs: List<Int>, expected: List<Int?>) -> String

/**
 * Plain-language row titles, written as the behaviour the row checks: `1 OR 1 = 1`,
 * `200 + 100 = 44, carry 1`, `q=1, hold`. Arithmetic reads in decimal; other buses in hex.
 */
internal object RowTitles {
    private fun hex8(v: Int?) = v?.let { formatValue(it, 8) } ?: "?"
    private fun carry(c: Int?) = if (c == 1) ", carry 1" else ""

    fun infix(op: String): RowTitle = { i, e -> "${i[0]} $op ${i[1]} = ${e[0]}" }

    val not: RowTitle = { i, e -> "NOT ${i[0]} = ${e[0]}" }

    val mux: RowTitle = { i, e -> "a=${i[0]} b=${i[1]}, s=${i[2]} picks ${if (i[2] == 1) "b" else "a"} = ${e[0]}" }

    val dmux: RowTitle = { i, e -> "send ${i[0]} to ${if (i[1] == 1) "b" else "a"} → a=${e[0]} b=${e[1]}" }

    val dec2: RowTitle = { i, _ -> "select ${i[0] * 2 + i[1]} → y${i[0] * 2 + i[1]} = 1" }

    val nibbleSwap: RowTitle = { i, e -> "swap ${hex8(i[0])} → ${hex8(e[0])}" }

    val busMux: RowTitle = { i, e -> "s=${i[2]} picks ${if (i[2] == 1) "b" else "a"} = ${hex8(e[0])}" }

    val half: RowTitle = { i, e -> "${i[0]} + ${i[1]} = ${e[0]}${carry(e[1])}" }

    val full: RowTitle = { i, e -> "${i[0]} + ${i[1]} + ${i[2]} = ${e[0]}${carry(e[1])}" }

    val add: RowTitle = { i, e ->
        val cin = if (i[2] == 1) " + 1" else ""
        "${i[0]} + ${i[1]}$cin = ${e[0]}${carry(e[1])}"
    }

    val inc: RowTitle = { i, e -> "${i[0]} + 1 = ${e[0]}" }

    val sub: RowTitle = { i, e -> "${i[0]} − ${i[1]} = ${e[0]}" }

    val zero: RowTitle = { i, e -> "${i[0]} is zero? ${if (e[0] == 1) "yes" else "no"}" }

    val alu: RowTitle = { i, e ->
        val body = when (i[2] * 2 + i[3]) {
            0 -> "${i[0]} + ${i[1]}"
            1 -> "${i[0]} − ${i[1]}"
            2 -> "${i[0]} AND ${i[1]}"
            else -> "pass ${i[1]}"
        }
        "$body = ${e[0]}${if (e[1] == 1) ", zero" else ""}"
    }

    private val opNames = listOf("LDA", "STA", "ADD", "SUB", "LDI", "JMP", "JZ", "HLT")
    private val aluNames = listOf("add", "subtract", "AND", "pass b")
    private val lineNames = listOf("load acc", "write memory", "use immediate", "jump", "halt")

    val decoder: RowTitle = { i, e ->
        val op = i[0] and 7
        val name = opNames[op] + if (op == Isa.JZ) " with z=${i[1]}" else ""
        val aluOp = e[0]?.let { hi -> e[1]?.let { lo -> hi * 2 + lo } }
        val alu = listOfNotNull(aluOp?.let { "ALU ${aluNames[it]}" })
        val lines = lineNames.filterIndexed { k, _ -> e[k + 2] == 1 }
        val active = (alu + lines).ifEmpty { listOf("nothing") }
        "$name → ${active.joinToString()}"
    }

    val srLatch: RowTitle = { i, e ->
        val action = when {
            i[0] == 1 -> "set"
            i[1] == 1 -> "reset"
            else -> "hold"
        }
        "$action → q=${e[0]}"
    }

    val dLatch: RowTitle = { i, e -> if (i[1] == 1) "en=1, d=${i[0]} → q=${e[0]}" else "en=0, hold → q=${e[0]}" }

    val bit: RowTitle = { i, e -> "q=${e[0]}, then ${if (i[1] == 1) "store ${i[0]}" else "hold"}" }

    val reg8: RowTitle = { i, e -> "q=${hex8(e[0])}, then ${if (i[1] == 1) "store ${hex8(i[0])}" else "hold"}" }

    val pc4: RowTitle = { i, e ->
        val next = when {
            i[1] == 1 -> "jump to ${i[2]}"
            i[0] == 1 -> "count"
            else -> "hold"
        }
        "pc=${e[0]}, then $next"
    }

    val ram4: RowTitle = { i, e ->
        val addr = i[0] * 2 + i[1]
        val write = if (i[3] == 1) ", then write ${hex8(i[2])}" else ""
        "read [$addr] = ${hex8(e[0])}$write"
    }
}
