package com.vayunmathur.games.logicgate.domain.model

/**
 * Every chip in the game. A level's reward chip runs here natively — trusted, fast, and never
 * expanded into the player's own build — which is what keeps the CPU levels cheap to simulate.
 */
object Chips {
    const val NAND = "NAND"
    const val HIGH = "HIGH"
    const val LOW = "LOW"
    const val NOT = "NOT"
    const val AND = "AND"
    const val OR = "OR"
    const val NOR = "NOR"
    const val XOR = "XOR"
    const val XNOR = "XNOR"
    const val MUX = "MUX"
    const val DMUX = "DMUX"
    const val DEC2 = "DEC2"
    const val SPLIT8 = "SPLIT8"
    const val JOIN8 = "JOIN8"
    const val SPLIT4 = "SPLIT4"
    const val JOIN4 = "JOIN4"
    const val NIB_SPLIT = "NIB_SPLIT"
    const val NIB_JOIN = "NIB_JOIN"
    const val NOT8 = "NOT8"
    const val AND8 = "AND8"
    const val OR8 = "OR8"
    const val XOR8 = "XOR8"
    const val MUX8B = "MUX8b"
    const val MUX4B = "MUX4b"
    const val HALF = "HALF"
    const val FULL = "FULL"
    const val ADD4 = "ADD4"
    const val ADD8 = "ADD8"
    const val INC4 = "INC4"
    const val SUB8 = "SUB8"
    const val ZERO8 = "ZERO8"
    const val ALU8 = "ALU8"
    const val DFF = "DFF"
    const val DFF4 = "DFF4"
    const val DFF8 = "DFF8"
    const val BIT = "BIT"
    const val REG8 = "REG8"
    const val PC4 = "PC4"
    const val RAM4 = "RAM4"
    const val RAM16 = "RAM16"
    const val DECODER = "DECODER"
    const val CPU = "CPU"

    /** Chips every level offers. */
    val base: List<String> = listOf(NAND, HIGH, LOW)

    private fun p(name: String, width: Int = 1) = Pin(name, width)
    private fun bits(prefix: String, n: Int) = (0 until n).map { p("$prefix$it") }

    private fun comb(
        id: String,
        inputs: List<Pin>,
        outputs: List<Pin>,
        cost: Int,
        f: (IntArray) -> IntArray,
    ) = ChipSpec(id, inputs, outputs, cost, Behavior.Comb(f))

    private fun gate2(id: String, cost: Int, f: (Int, Int) -> Int) =
        comb(id, listOf(p("a"), p("b")), listOf(p("o")), cost) { intArrayOf(f(it[0], it[1]) and 1) }

    private fun split(id: String, n: Int) =
        comb(id, listOf(p("x", n)), bits("b", n), 0) { v -> IntArray(n) { (v[0] shr it) and 1 } }

    private fun join(id: String, n: Int) =
        comb(id, bits("b", n), listOf(p("x", n)), 0) { v ->
            intArrayOf(v.foldIndexed(0) { i, acc, b -> acc or ((b and 1) shl i) })
        }

    private fun bitwise8(id: String, cost: Int, f: (Int, Int) -> Int) =
        comb(id, listOf(p("a", 8), p("b", 8)), listOf(p("o", 8)), cost) { intArrayOf(f(it[0], it[1]) and 0xFF) }

    private val logic = listOf(
        gate2(NAND, 1) { a, b -> (a and b) xor 1 },
        comb(HIGH, emptyList(), listOf(p("o")), 0) { intArrayOf(1) },
        comb(LOW, emptyList(), listOf(p("o")), 0) { intArrayOf(0) },
        comb(NOT, listOf(p("a")), listOf(p("o")), 1) { intArrayOf(it[0] xor 1) },
        gate2(AND, 2) { a, b -> a and b },
        gate2(OR, 3) { a, b -> a or b },
        gate2(NOR, 4) { a, b -> (a or b) xor 1 },
        gate2(XOR, 4) { a, b -> a xor b },
        gate2(XNOR, 5) { a, b -> a xor b xor 1 },
    )

    private val routing = listOf(
        comb(MUX, listOf(p("a"), p("b"), p("s")), listOf(p("o")), 4) {
            intArrayOf(if (it[2] == 1) it[1] else it[0])
        },
        comb(DMUX, listOf(p("in"), p("s")), listOf(p("a"), p("b")), 5) {
            intArrayOf(it[0] and (it[1] xor 1), it[0] and it[1])
        },
        comb(DEC2, listOf(p("s1"), p("s0")), bits("y", 4), 10) { v ->
            val sel = v[0] * 2 + v[1]
            IntArray(4) { if (it == sel) 1 else 0 }
        },
        split(SPLIT8, 8),
        join(JOIN8, 8),
        split(SPLIT4, 4),
        join(JOIN4, 4),
        comb(NIB_SPLIT, listOf(p("x", 8)), listOf(p("lo", 4), p("hi", 4)), 0) {
            intArrayOf(it[0] and 0xF, it[0] shr 4)
        },
        comb(NIB_JOIN, listOf(p("lo", 4), p("hi", 4)), listOf(p("x", 8)), 0) {
            intArrayOf(it[0] or (it[1] shl 4))
        },
        comb(NOT8, listOf(p("a", 8)), listOf(p("o", 8)), 8) { intArrayOf(it[0].inv() and 0xFF) },
        bitwise8(AND8, 16) { a, b -> a and b },
        bitwise8(OR8, 24) { a, b -> a or b },
        bitwise8(XOR8, 32) { a, b -> a xor b },
        comb(MUX8B, listOf(p("a", 8), p("b", 8), p("s")), listOf(p("o", 8)), 32) {
            intArrayOf(if (it[2] == 1) it[1] else it[0])
        },
        comb(MUX4B, listOf(p("a", 4), p("b", 4), p("s")), listOf(p("o", 4)), 16) {
            intArrayOf(if (it[2] == 1) it[1] else it[0])
        },
    )

    private val arithmetic = listOf(
        comb(HALF, listOf(p("a"), p("b")), listOf(p("s"), p("c")), 5) {
            intArrayOf(it[0] xor it[1], it[0] and it[1])
        },
        comb(FULL, listOf(p("a"), p("b"), p("cin")), listOf(p("s"), p("cout")), 9) {
            val t = it[0] + it[1] + it[2]
            intArrayOf(t and 1, t shr 1)
        },
        comb(ADD4, listOf(p("a", 4), p("b", 4), p("cin")), listOf(p("s", 4), p("cout")), 36) {
            val t = it[0] + it[1] + it[2]
            intArrayOf(t and 0xF, t shr 4)
        },
        comb(ADD8, listOf(p("a", 8), p("b", 8), p("cin")), listOf(p("s", 8), p("cout")), 72) {
            val t = it[0] + it[1] + it[2]
            intArrayOf(t and 0xFF, t shr 8)
        },
        comb(INC4, listOf(p("a", 4)), listOf(p("o", 4)), 16) { intArrayOf((it[0] + 1) and 0xF) },
        comb(SUB8, listOf(p("a", 8), p("b", 8)), listOf(p("d", 8)), 80) { intArrayOf((it[0] - it[1]) and 0xFF) },
        comb(ZERO8, listOf(p("a", 8)), listOf(p("z")), 22) { intArrayOf(if (it[0] == 0) 1 else 0) },
        comb(ALU8, listOf(p("a", 8), p("b", 8), p("op1"), p("op0")), listOf(p("o", 8), p("z")), 206) {
            val r = alu(it[0], it[1], it[2] * 2 + it[3])
            intArrayOf(r, if (r == 0) 1 else 0)
        },
    )

    private fun register(id: String, width: Int, cost: Int, withLoad: Boolean): ChipSpec {
        val ins = if (withLoad) listOf(p("d", width), p("load")) else listOf(p("d", width))
        return ChipSpec(
            id, ins, listOf(p("q", width)), cost,
            Behavior.Seq(
                init = IntArray(1),
                outputs = { s, _ -> intArrayOf(s[0]) },
                next = { s, i -> intArrayOf(if (!withLoad || i[1] == 1) i[0] else s[0]) },
            ),
        )
    }

    private val memory = listOf(
        register(DFF, 1, 8, withLoad = false),
        register(DFF4, 4, 32, withLoad = false),
        register(DFF8, 8, 64, withLoad = false),
        register(BIT, 1, 12, withLoad = true),
        register(REG8, 8, 96, withLoad = true),
        ChipSpec(
            PC4, listOf(p("en"), p("load"), p("v", 4)), listOf(p("pc", 4)), 80,
            Behavior.Seq(
                init = IntArray(1),
                outputs = { s, _ -> intArrayOf(s[0]) },
                next = { s, i -> intArrayOf(pcNext(s[0], i[0], i[1], i[2])) },
            ),
        ),
        ChipSpec(
            RAM4, listOf(p("a1"), p("a0"), p("din", 8), p("we")), listOf(p("dout", 8)), 498,
            Behavior.Seq(
                init = IntArray(4),
                outputs = { s, i -> intArrayOf(s[i[0] * 2 + i[1]]) },
                next = { s, i -> s.copyOf().also { if (i[3] == 1) it[i[0] * 2 + i[1]] = i[2] } },
                readsInputs = true,
            ),
        ),
        ChipSpec(
            RAM16, listOf(p("iaddr", 4), p("daddr", 4), p("din", 8), p("we")),
            listOf(p("instr", 8), p("dout", 8)), 2560,
            Behavior.Seq(
                init = IntArray(16),
                outputs = { s, i -> intArrayOf(s[i[0]], s[i[1]]) },
                next = { s, i -> s.copyOf().also { if (i[3] == 1) it[i[1]] = i[2] } },
                readsInputs = true,
            ),
        ),
    )

    private val cpu = listOf(
        comb(DECODER, listOf(p("op", 4), p("z")), Isa.decoderOutputs, 44) { Isa.decode(it[0], it[1]) },
        ChipSpec(
            CPU, listOf(p("instr", 8), p("data", 8)), Isa.cpuOutputs, 481,
            Behavior.Seq(
                init = IntArray(2),
                outputs = { s, i -> Isa.cpuOut(s[0], s[1], i[0]) },
                next = { s, i -> Isa.cpuNext(s[0], s[1], i[0], i[1]) },
                readsInputs = true,
            ),
        ),
    )

    val all: List<ChipSpec> = logic + routing + arithmetic + memory + cpu

    private val byId: Map<String, ChipSpec> = all.associateBy { it.id }

    operator fun get(id: String): ChipSpec? = byId[id]

    /** Pin lookup for a board with the level pins [io], resolving chips through [spec]. */
    fun lookup(io: LevelIo, spec: (String) -> ChipSpec? = { byId[it] }): PinLookup = PinLookup { node ->
        when (node) {
            is Gate -> spec(node.chipId)?.let { NodePins(it.inputs, it.outputs) }
            is LevelInput -> io.inputs.getOrNull(node.index)?.let { NodePins(emptyList(), listOf(it)) }
            is LevelOutput -> io.outputs.getOrNull(node.index)?.let { NodePins(listOf(it), emptyList()) }
        }
    }

    /** `00` ADD · `01` SUB · `10` AND · `11` PASS B, all 8-bit wrapping. */
    fun alu(a: Int, b: Int, op: Int): Int = when (op) {
        0 -> a + b
        1 -> a - b
        2 -> a and b
        else -> b
    } and 0xFF

    fun pcNext(pc: Int, en: Int, load: Int, v: Int): Int = when {
        load == 1 -> v
        en == 1 -> (pc + 1) and 0xF
        else -> pc
    }
}
