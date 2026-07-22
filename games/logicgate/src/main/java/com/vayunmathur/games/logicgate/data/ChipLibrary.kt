package com.vayunmathur.games.logicgate.data

/**
 * Core definitions for all chips.
 * Each chip is a functional block with named pins and a pure boolean evaluation.
 * NAND is primitive (cost 1). Everything else's cost is flattened NAND count.
 */

data class ChipDef(
    val id: String,
    val displayName: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val nandCost: Int,
    val description: String,
    val category: ChipCategory,
    val eval: (List<Boolean>) -> List<Boolean>
) {
    val inputCount: Int get() = inputs.size
    val outputCount: Int get() = outputs.size
}

enum class ChipCategory { PRIMITIVE, FOUNDATION, ROUTING, ARITH, MEMORY, CPU }

object ChipLibrary {

    private fun b(v: Boolean) = v

    val NAND = ChipDef(
        id = "NAND",
        displayName = "NAND",
        inputs = listOf("a", "b"),
        outputs = listOf("out"),
        nandCost = 1,
        description = "Primitive: NOT (A AND B)",
        category = ChipCategory.PRIMITIVE,
        eval = { inp -> listOf(!(inp[0] && inp[1])) }
    )

    val NOT = ChipDef(
        id = "NOT",
        displayName = "NOT",
        inputs = listOf("in"),
        outputs = listOf("out"),
        nandCost = 1,
        description = "Inverter: NAND(a,a)",
        category = ChipCategory.FOUNDATION,
        eval = { inp -> listOf(!inp[0]) }
    )

    val AND = ChipDef(
        id = "AND",
        displayName = "AND",
        inputs = listOf("a", "b"),
        outputs = listOf("out"),
        nandCost = 2,
        description = "Conjunction",
        category = ChipCategory.FOUNDATION,
        eval = { inp -> listOf(inp[0] && inp[1]) }
    )

    val OR = ChipDef(
        id = "OR",
        displayName = "OR",
        inputs = listOf("a", "b"),
        outputs = listOf("out"),
        nandCost = 3,
        description = "Disjunction",
        category = ChipCategory.FOUNDATION,
        eval = { inp -> listOf(inp[0] || inp[1]) }
    )

    val XOR = ChipDef(
        id = "XOR",
        displayName = "XOR",
        inputs = listOf("a", "b"),
        outputs = listOf("out"),
        nandCost = 4,
        description = "Exclusive OR",
        category = ChipCategory.FOUNDATION,
        eval = { inp -> listOf(inp[0] xor inp[1]) }
    )

    val NOR = ChipDef(
        id = "NOR",
        displayName = "NOR",
        inputs = listOf("a", "b"),
        outputs = listOf("out"),
        nandCost = 4,
        description = "NOT OR",
        category = ChipCategory.FOUNDATION,
        eval = { inp -> listOf(!(inp[0] || inp[1])) }
    )

    val XNOR = ChipDef(
        id = "XNOR",
        displayName = "XNOR",
        inputs = listOf("a", "b"),
        outputs = listOf("out"),
        nandCost = 5,
        description = "NOT XOR",
        category = ChipCategory.FOUNDATION,
        eval = { inp -> listOf(!(inp[0] xor inp[1])) }
    )

    val MUX = ChipDef(
        id = "MUX",
        displayName = "MUX",
        inputs = listOf("a", "b", "sel"),
        outputs = listOf("out"),
        nandCost = 4,
        description = "If sel then b else a",
        category = ChipCategory.ROUTING,
        eval = { inp ->
            val a = inp[0]; val b = inp[1]; val sel = inp[2]
            listOf(if (sel) b else a)
        }
    )

    val DMUX = ChipDef(
        id = "DMUX",
        displayName = "DMUX",
        inputs = listOf("in", "sel"),
        outputs = listOf("a", "b"),
        nandCost = 3,
        description = "Demultiplex: route in to a or b",
        category = ChipCategory.ROUTING,
        eval = { inp ->
            val inn = inp[0]; val sel = inp[1]
            if (sel) listOf(false, inn) else listOf(inn, false)
        }
    )

    val MUX4 = ChipDef(
        id = "MUX4",
        displayName = "MUX4",
        inputs = listOf("a", "b", "c", "d", "sel0", "sel1"),
        outputs = listOf("out"),
        nandCost = 12,
        description = "4-way mux, sel1 sel0 = 00:a 01:b 10:c 11:d",
        category = ChipCategory.ROUTING,
        eval = { inp ->
            val sel = (if (inp[5]) 2 else 0) + (if (inp[4]) 1 else 0)
            listOf(inp[sel])
        }
    )

    val HALF_ADDER = ChipDef(
        id = "HALF_ADDER",
        displayName = "HalfAdder",
        inputs = listOf("a", "b"),
        outputs = listOf("sum", "carry"),
        nandCost = 6,
        description = "sum = a XOR b, carry = a AND b",
        category = ChipCategory.ARITH,
        eval = { inp ->
            val a = inp[0]; val b = inp[1]
            listOf(a xor b, a && b)
        }
    )

    val FULL_ADDER = ChipDef(
        id = "FULL_ADDER",
        displayName = "FullAdder",
        inputs = listOf("a", "b", "cin"),
        outputs = listOf("sum", "cout"),
        nandCost = 14,
        description = "Full adder with carry in/out",
        category = ChipCategory.ARITH,
        eval = { inp ->
            val a = inp[0]; val b = inp[1]; val cin = inp[2]
            val sum = a xor b xor cin
            val cout = (a && b) || (b && cin) || (a && cin)
            listOf(sum, cout)
        }
    )

    // 2-bit adder for progression (4-bit uses many FullAdders, truth table explosion - we handle via multi-bit API)
    val ADDER_4 = ChipDef(
        id = "ADDER_4",
        displayName = "Adder4",
        inputs = listOf("a0","a1","a2","a3","b0","b1","b2","b3"),
        outputs = listOf("s0","s1","s2","s3","cout"),
        nandCost = 56,
        description = "4-bit ripple-carry adder",
        category = ChipCategory.ARITH,
        eval = { inp ->
            val a = bitsToInt(inp.slice(0..3))
            val b = bitsToInt(inp.slice(4..7))
            val sum = a + b
            intToBits(sum, 4) + listOf(sum >= 16)
        }
    )

    val ADDER_8 = ChipDef(
        id = "ADDER_8",
        displayName = "Adder8",
        inputs = (0..7).map { "a$it" } + (0..7).map { "b$it" },
        outputs = (0..7).map { "s$it" } + listOf("cout"),
        nandCost = 112,
        description = "8-bit ripple-carry adder",
        category = ChipCategory.ARITH,
        eval = { inp ->
            val a = bitsToInt(inp.slice(0..7))
            val b = bitsToInt(inp.slice(8..15))
            val sum = a + b
            intToBits(sum, 8) + listOf(sum >= 256)
        }
    )

    val DFF = ChipDef(
        id = "DFF",
        displayName = "DFF",
        inputs = listOf("d", "clk"),
        outputs = listOf("q"),
        nandCost = 6,
        description = "D Flip-Flop (primitive for memory)",
        category = ChipCategory.MEMORY,
        eval = { inp -> listOf(inp[0]) } // sequential handled specially, combinational sees wire-through
    )

    val BIT = ChipDef(
        id = "BIT",
        displayName = "Bit",
        inputs = listOf("in", "load"),
        outputs = listOf("out"),
        nandCost = 12,
        description = "1-bit register, load=1 stores in",
        category = ChipCategory.MEMORY,
        eval = { inp -> listOf(inp[0]) }
    )

    val REGISTER_8 = ChipDef(
        id = "REG_8",
        displayName = "Reg8",
        inputs = (0..7).map { "in$it" } + listOf("load"),
        outputs = (0..7).map { "out$it" },
        nandCost = 96,
        description = "8-bit register",
        category = ChipCategory.MEMORY,
        eval = { inp -> inp.slice(0..7) }
    )

    val ALU_8 = ChipDef(
        id = "ALU_8",
        displayName = "ALU",
        inputs = (0..7).map { "a$it" } + (0..7).map { "b$it" } + listOf("op0","op1"),
        outputs = (0..7).map { "out$it" } + listOf("zero","carry"),
        nandCost = 200,
        description = "ALU: op 00=ADD 01=AND 10=OR 11=XOR",
        category = ChipCategory.CPU,
        eval = { inp ->
            val a = bitsToInt(inp.slice(0..7))
            val b = bitsToInt(inp.slice(8..15))
            val op = (if (inp[17]) 2 else 0) + (if (inp[16]) 1 else 0)
            val result = when (op) {
                0 -> a + b
                1 -> a and b
                2 -> a or b
                else -> a xor b
            } and 0xFF
            intToBits(result, 8) + listOf(result == 0, result > 255 || op == 0 && a + b >= 256)
        }
    )

    val CPU_8 = ChipDef(
        id = "CPU_8",
        displayName = "CPU",
        inputs = (0..7).map { "in$it" } + listOf("clk", "reset"),
        outputs = (0..7).map { "out$it" } + (0..7).map { "addr$it" } + listOf("write"),
        nandCost = 2000,
        description = "Simple 8-bit CPU",
        category = ChipCategory.CPU,
        eval = { inp -> List(17) { false } }
    )

    // All chips map
    val all: Map<String, ChipDef> = listOf(
        NAND, NOT, AND, OR, XOR, NOR, XNOR,
        MUX, DMUX, MUX4,
        HALF_ADDER, FULL_ADDER, ADDER_4, ADDER_8,
        DFF, BIT, REGISTER_8,
        ALU_8, CPU_8
    ).associateBy { it.id }

    fun get(id: String): ChipDef = all[id] ?: error("Unknown chip $id")

    // Helper for bit conversion
    fun bitsToInt(bits: List<Boolean>): Int {
        var v = 0
        bits.forEachIndexed { i, bit -> if (bit) v = v or (1 shl i) }
        return v
    }

    fun intToBits(value: Int, width: Int): List<Boolean> {
        return List(width) { i -> (value shr i) and 1 == 1 }
    }
}
