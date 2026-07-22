package com.vayunmathur.games.logicgate.data

/**
 * Level progression DAG: max 2 open at any time, chapters separated.
 * Prereqs define unlock. Optimality removed - pure completion gated.
 */

enum class ChapterId { FOUNDATION, ROUTING, ARITH, MEMORY, CPU }

data class Chapter(
    val id: ChapterId,
    val name: String,
    val desc: String,
    val levelIds: List<String>
)

data class LevelDef(
    val id: String,
    val displayName: String,
    val chapter: ChapterId,
    val description: String,
    val hint: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val targetChipId: String,
    val allowedChipIds: List<String>,
    val flavor: String = "",
    val unlocksChipId: String? = null,
    val prereqs: List<String> = emptyList()
)

object Levels {
    val all: List<LevelDef> = listOf(
        LevelDef(
            id = "NOT",
            displayName = "NOT",
            chapter = ChapterId.FOUNDATION,
            description = "Build NOT using only NAND.",
            hint = "NAND(in,in) = NOT(in)",
            inputs = listOf("in"),
            outputs = listOf("out"),
            targetChipId = "NOT",
            allowedChipIds = listOf("NAND"),
            flavor = "Everything starts with NAND.",
            unlocksChipId = "NOT",
            prereqs = emptyList()
        ),
        LevelDef(
            id = "AND",
            displayName = "AND",
            chapter = ChapterId.FOUNDATION,
            description = "Build AND: out = a & b",
            hint = "NAND then NOT.",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "AND",
            allowedChipIds = listOf("NAND", "NOT"),
            flavor = "NOT unlocked! Now combine.",
            unlocksChipId = "AND",
            prereqs = listOf("NOT")
        ),
        LevelDef(
            id = "OR",
            displayName = "OR",
            chapter = ChapterId.FOUNDATION,
            description = "Build OR: out = a | b",
            hint = "De Morgan: NAND(NOT a, NOT b)",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "OR",
            allowedChipIds = listOf("NAND", "NOT", "AND"),
            flavor = "De Morgan is your friend.",
            unlocksChipId = "OR",
            prereqs = listOf("AND")
        ),
        LevelDef(
            id = "XOR",
            displayName = "XOR",
            chapter = ChapterId.FOUNDATION,
            description = "Build XOR: out = a ^ b",
            hint = "4 NANDs tricky build.",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "XOR",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR"),
            flavor = "The key to addition.",
            unlocksChipId = "XOR",
            prereqs = listOf("OR")
        ),
        LevelDef(
            id = "NOR",
            displayName = "NOR",
            chapter = ChapterId.FOUNDATION,
            description = "Build NOR: out = !(a | b)",
            hint = "OR then NOT.",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "NOR",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR"),
            unlocksChipId = "NOR",
            prereqs = listOf("XOR")
        ),
        LevelDef(
            id = "XNOR",
            displayName = "XNOR",
            chapter = ChapterId.FOUNDATION,
            description = "Build XNOR: equality",
            hint = "XOR then NOT.",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "XNOR",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "NOR"),
            unlocksChipId = "XNOR",
            prereqs = listOf("XOR")
        ),
        // ROUTING
        LevelDef(
            id = "MUX",
            displayName = "MUX",
            chapter = ChapterId.ROUTING,
            description = "If sel=0 out=a else b",
            hint = "(a & !sel) | (b & sel)",
            inputs = listOf("a", "b", "sel"),
            outputs = listOf("out"),
            targetChipId = "MUX",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR"),
            flavor = "Choose between signals.",
            unlocksChipId = "MUX",
            prereqs = listOf("NOR", "XNOR")
        ),
        LevelDef(
            id = "DMUX",
            displayName = "DMUX",
            chapter = ChapterId.ROUTING,
            description = "Route in to a if sel=0 else b",
            hint = "a = in & !sel, b = in & sel",
            inputs = listOf("in", "sel"),
            outputs = listOf("a", "b"),
            targetChipId = "DMUX",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX"),
            unlocksChipId = "DMUX",
            prereqs = listOf("MUX")
        ),
        LevelDef(
            id = "MUX4",
            displayName = "MUX4",
            chapter = ChapterId.ROUTING,
            description = "sel1 sel0: 00->a 01->b 10->c 11->d",
            hint = "3 MUXes.",
            inputs = listOf("a", "b", "c", "d", "sel0", "sel1"),
            outputs = listOf("out"),
            targetChipId = "MUX4",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX", "DMUX"),
            unlocksChipId = "MUX4",
            prereqs = listOf("MUX")
        ),
        // ARITH
        LevelDef(
            id = "HALF_ADDER",
            displayName = "Half Adder",
            chapter = ChapterId.ARITH,
            description = "sum=a^b carry=a&b",
            hint = "XOR + AND",
            inputs = listOf("a", "b"),
            outputs = listOf("sum", "carry"),
            targetChipId = "HALF_ADDER",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX"),
            flavor = "1-bit addition.",
            unlocksChipId = "HALF_ADDER",
            prereqs = listOf("DMUX", "MUX4")
        ),
        LevelDef(
            id = "FULL_ADDER",
            displayName = "Full Adder",
            chapter = ChapterId.ARITH,
            description = "Add with carry",
            hint = "Two half-adders + OR",
            inputs = listOf("a", "b", "cin"),
            outputs = listOf("sum", "cout"),
            targetChipId = "FULL_ADDER",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX", "HALF_ADDER"),
            flavor = "Brick of binary addition.",
            unlocksChipId = "FULL_ADDER",
            prereqs = listOf("HALF_ADDER")
        ),
        LevelDef(
            id = "ADDER_4",
            displayName = "Adder4",
            chapter = ChapterId.ARITH,
            description = "Add two 4-bit numbers",
            hint = "Chain FullAdders",
            inputs = listOf("a0","a1","a2","a3","b0","b1","b2","b3"),
            outputs = listOf("s0","s1","s2","s3","cout"),
            targetChipId = "ADDER_4",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "HALF_ADDER", "FULL_ADDER"),
            unlocksChipId = "ADDER_4",
            prereqs = listOf("HALF_ADDER")
        ),
        LevelDef(
            id = "ADDER_8",
            displayName = "Adder8",
            chapter = ChapterId.ARITH,
            description = "Add two 8-bit numbers",
            hint = "Chain 8 FullAdders",
            inputs = (0..7).map { "a$it" } + (0..7).map { "b$it" },
            outputs = (0..7).map { "s$it" } + listOf("cout"),
            targetChipId = "ADDER_8",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "HALF_ADDER", "FULL_ADDER", "ADDER_4"),
            flavor = "Heart of CPU.",
            unlocksChipId = "ADDER_8",
            prereqs = listOf("FULL_ADDER", "ADDER_4")
        ),
        // MEMORY
        LevelDef(
            id = "DFF",
            displayName = "DFF",
            chapter = ChapterId.MEMORY,
            description = "On tick, Q = D",
            hint = "NAND latch combinational sim.",
            inputs = listOf("d", "clk"),
            outputs = listOf("q"),
            targetChipId = "DFF",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX"),
            flavor = "Time enters.",
            unlocksChipId = "DFF",
            prereqs = listOf("ADDER_8")
        ),
        LevelDef(
            id = "BIT",
            displayName = "Bit",
            chapter = ChapterId.MEMORY,
            description = "If load=1 store in",
            hint = "MUX + DFF",
            inputs = listOf("in", "load"),
            outputs = listOf("out"),
            targetChipId = "BIT",
            allowedChipIds = ChipLibrary.all.keys.filter { it != "CPU_8" && it != "REG_8" && it != "ALU_8" }.toList(),
            unlocksChipId = "BIT",
            prereqs = listOf("DFF")
        ),
        LevelDef(
            id = "REG_8",
            displayName = "Reg8",
            chapter = ChapterId.MEMORY,
            description = "8-bit register with load",
            hint = "8x BIT sharing load",
            inputs = (0..7).map { "in$it" } + listOf("load"),
            outputs = (0..7).map { "out$it" },
            targetChipId = "REG_8",
            allowedChipIds = ChipLibrary.all.keys.filter { it != "CPU_8" && it != "ALU_8" }.toList(),
            unlocksChipId = "REG_8",
            prereqs = listOf("BIT")
        ),
        // CPU
        LevelDef(
            id = "ALU_8",
            displayName = "ALU",
            chapter = ChapterId.CPU,
            description = "op 00=ADD 01=AND 10=OR 11=XOR + flags",
            hint = "Mux between results",
            inputs = (0..7).map { "a$it" } + (0..7).map { "b$it" } + listOf("op0","op1"),
            outputs = (0..7).map { "out$it" } + listOf("zero","carry"),
            targetChipId = "ALU_8",
            allowedChipIds = ChipLibrary.all.keys.filter { it != "CPU_8" }.toList(),
            flavor = "Calculator of CPU.",
            unlocksChipId = "ALU_8",
            prereqs = listOf("REG_8")
        ),
        LevelDef(
            id = "CPU_8",
            displayName = "CPU",
            chapter = ChapterId.CPU,
            description = "8-bit CPU capstone",
            hint = "ALU + Registers + MUX",
            inputs = (0..7).map { "in$it" } + listOf("clk", "reset"),
            outputs = (0..7).map { "out$it" } + (0..7).map { "addr$it" } + listOf("write"),
            targetChipId = "CPU_8",
            allowedChipIds = ChipLibrary.all.keys.toList(),
            flavor = "NAND to computer. Done.",
            unlocksChipId = "CPU_8",
            prereqs = listOf("ALU_8")
        )
    )

    val chapters: List<Chapter> = listOf(
        Chapter(ChapterId.FOUNDATION, "Foundation", "Build basic gates", all.filter { it.chapter == ChapterId.FOUNDATION }.map { it.id }),
        Chapter(ChapterId.ROUTING, "Routing", "Steer signals", all.filter { it.chapter == ChapterId.ROUTING }.map { it.id }),
        Chapter(ChapterId.ARITH, "Arithmetic", "Make numbers add", all.filter { it.chapter == ChapterId.ARITH }.map { it.id }),
        Chapter(ChapterId.MEMORY, "Memory", "Remember", all.filter { it.chapter == ChapterId.MEMORY }.map { it.id }),
        Chapter(ChapterId.CPU, "CPU", "The machine", all.filter { it.chapter == ChapterId.CPU }.map { it.id }),
    )

    val byId: Map<String, LevelDef> = all.associateBy { it.id }
    fun get(id: String): LevelDef = byId[id] ?: error("Unknown level $id")

    val timelineRows: List<List<String>> = listOf(
        listOf("NOT"),
        listOf("AND"),
        listOf("OR"),
        listOf("XOR"),
        listOf("NOR", "XNOR"),
        listOf("MUX"),
        listOf("DMUX", "MUX4"),
        listOf("HALF_ADDER"),
        listOf("FULL_ADDER", "ADDER_4"),
        listOf("ADDER_8"),
        listOf("DFF"),
        listOf("BIT"),
        listOf("REG_8"),
        listOf("ALU_8"),
        listOf("CPU_8")
    )

    fun availableLevels(completed: Set<String>): Set<String> {
        return all.filter { it.id !in completed && it.prereqs.all { p -> p in completed } }.map { it.id }.toSet()
    }
}
