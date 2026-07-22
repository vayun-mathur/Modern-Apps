package com.vayunmathur.games.logicgate.data

/**
 * Level progression: NAND -> foundations -> routing -> arithmetic -> memory -> CPU
 * Each level defines I/O interface and expected truth table computed from reference chip.
 * Player must wire available chips to satisfy that truth table with minimal NAND cost.
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
    /** inputs exposed to player circuit (names) */
    val inputs: List<String>,
    /** outputs expected (names) */
    val outputs: List<String>,
    /** underlying target chip whose truth table we validate against */
    val targetChipId: String,
    /** chips allowed in inventory (including NAND always) - unlocked chips */
    val allowedChipIds: List<String>,
    /** optimal flattened NAND count for 3 stars */
    val optimalNands: Int,
    /** narrative */
    val flavor: String = "",
    /** wether this level IS the gate introduction (unlocks its chip) */
    val unlocksChipId: String? = null
)

object Levels {
    val all: List<LevelDef> = listOf(
        // FOUNDATION
        LevelDef(
            id = "NOT",
            displayName = "NOT Gate",
            chapter = ChapterId.FOUNDATION,
            description = "Build NOT using only NAND. Output is inverse of input.",
            hint = "Tie both NAND inputs together: NAND(in,in) = NOT(in)",
            inputs = listOf("in"),
            outputs = listOf("out"),
            targetChipId = "NOT",
            allowedChipIds = listOf("NAND"),
            optimalNands = 1,
            flavor = "Everything starts with NAND. Can you make the simplest gate?",
            unlocksChipId = "NOT"
        ),
        LevelDef(
            id = "AND",
            displayName = "AND Gate",
            chapter = ChapterId.FOUNDATION,
            description = "Build AND: out = a & b",
            hint = "NAND then NOT. Or NAND(a,b) -> invert.",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "AND",
            allowedChipIds = listOf("NAND", "NOT"),
            optimalNands = 2,
            flavor = "NOT unlocked! Now combine to get AND.",
            unlocksChipId = "AND"
        ),
        LevelDef(
            id = "OR",
            displayName = "OR Gate",
            chapter = ChapterId.FOUNDATION,
            description = "Build OR: out = a | b",
            hint = "De Morgan: OR = NOT(NOT a AND NOT b) = NAND(NOT a, NOT b)",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "OR",
            allowedChipIds = listOf("NAND", "NOT", "AND"),
            optimalNands = 3,
            flavor = "De Morgan is your friend.",
            unlocksChipId = "OR"
        ),
        LevelDef(
            id = "XOR",
            displayName = "XOR Gate",
            chapter = ChapterId.FOUNDATION,
            description = "Build XOR: out = a ^ b (different?)",
            hint = "XOR can be 4 NANDs: (a NAND b) NAND (a NAND (a NAND b)) NAND ((a NAND b) NAND b) ...",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "XOR",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR"),
            optimalNands = 4,
            flavor = "The tricky one. XOR is key to addition.",
            unlocksChipId = "XOR"
        ),
        LevelDef(
            id = "NOR",
            displayName = "NOR Gate",
            chapter = ChapterId.FOUNDATION,
            description = "Build NOR: out = !(a | b)",
            hint = "OR then NOT — or 4 NANDs directly.",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "NOR",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR"),
            optimalNands = 4,
            unlocksChipId = "NOR"
        ),
        LevelDef(
            id = "XNOR",
            displayName = "XNOR Gate",
            chapter = ChapterId.FOUNDATION,
            description = "Build XNOR: equality check",
            hint = "XOR then NOT.",
            inputs = listOf("a", "b"),
            outputs = listOf("out"),
            targetChipId = "XNOR",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "NOR"),
            optimalNands = 5,
            unlocksChipId = "XNOR"
        ),
        // ROUTING
        LevelDef(
            id = "MUX",
            displayName = "Multiplexer",
            chapter = ChapterId.ROUTING,
            description = "If sel=0 out=a else out=b",
            hint = "MUX = (a & !sel) | (b & sel)",
            inputs = listOf("a", "b", "sel"),
            outputs = listOf("out"),
            targetChipId = "MUX",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR"),
            optimalNands = 4,
            flavor = "First routing primitive — choose between two signals.",
            unlocksChipId = "MUX"
        ),
        LevelDef(
            id = "DMUX",
            displayName = "Demux",
            chapter = ChapterId.ROUTING,
            description = "Route in to a if sel=0 else b",
            hint = "a = in & !sel, b = in & sel",
            inputs = listOf("in", "sel"),
            outputs = listOf("a", "b"),
            targetChipId = "DMUX",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX"),
            optimalNands = 3,
            unlocksChipId = "DMUX"
        ),
        LevelDef(
            id = "MUX4",
            displayName = "4-Way MUX",
            chapter = ChapterId.ROUTING,
            description = "sel1 sel0: 00->a 01->b 10->c 11->d",
            hint = "Build from 3 MUXes or direct logic.",
            inputs = listOf("a", "b", "c", "d", "sel0", "sel1"),
            outputs = listOf("out"),
            targetChipId = "MUX4",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX", "DMUX"),
            optimalNands = 12,
            unlocksChipId = "MUX4"
        ),
        // ARITH
        LevelDef(
            id = "HALF_ADDER",
            displayName = "Half Adder",
            chapter = ChapterId.ARITH,
            description = "Add two bits: sum=a^b carry=a&b",
            hint = "You've already built XOR and AND!",
            inputs = listOf("a", "b"),
            outputs = listOf("sum", "carry"),
            targetChipId = "HALF_ADDER",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX"),
            optimalNands = 6,
            flavor = "The start of arithmetic — 1-bit addition without carry in.",
            unlocksChipId = "HALF_ADDER"
        ),
        LevelDef(
            id = "FULL_ADDER",
            displayName = "Full Adder",
            chapter = ChapterId.ARITH,
            description = "Add with carry: sum=a^b^cin, cout majority.",
            hint = "Use two half-adders + OR for cout, or optimized 14-NAND version.",
            inputs = listOf("a", "b", "cin"),
            outputs = listOf("sum", "cout"),
            targetChipId = "FULL_ADDER",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX", "HALF_ADDER"),
            optimalNands = 14,
            flavor = "Chainable adder — the brick of all binary addition.",
            unlocksChipId = "FULL_ADDER"
        ),
        LevelDef(
            id = "ADDER_4",
            displayName = "4-Bit Adder",
            chapter = ChapterId.ARITH,
            description = "Add two 4-bit numbers: a0..a3 + b0..b3",
            hint = "Chain 4 FullAdders ripple-carry style.",
            inputs = listOf("a0","a1","a2","a3","b0","b1","b2","b3"),
            outputs = listOf("s0","s1","s2","s3","cout"),
            targetChipId = "ADDER_4",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "HALF_ADDER", "FULL_ADDER"),
            optimalNands = 56,
            unlocksChipId = "ADDER_4"
        ),
        LevelDef(
            id = "ADDER_8",
            displayName = "8-Bit Adder",
            chapter = ChapterId.ARITH,
            description = "Add two 8-bit numbers. Carry out indicates overflow.",
            hint = "Chain 8 FullAdders. Low bits: a0,b0 are LSB.",
            inputs = (0..7).map { "a$it" } + (0..7).map { "b$it" },
            outputs = (0..7).map { "s$it" } + listOf("cout"),
            targetChipId = "ADDER_8",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "HALF_ADDER", "FULL_ADDER", "ADDER_4"),
            optimalNands = 112,
            flavor = "First 8-bit component! This will be the heart of our CPU.",
            unlocksChipId = "ADDER_8"
        ),
        // MEMORY
        LevelDef(
            id = "DFF",
            displayName = "D Flip-Flop",
            chapter = ChapterId.MEMORY,
            description = "On clock tick, Q = D. Foundation of memory.",
            hint = "NAND latch with clock gating. For this simulator, we model combinationally.",
            inputs = listOf("d", "clk"),
            outputs = listOf("q"),
            targetChipId = "DFF",
            allowedChipIds = listOf("NAND", "NOT", "AND", "OR", "XOR", "MUX"),
            optimalNands = 6,
            flavor = "Time enters the picture. This is how computers remember.",
            unlocksChipId = "DFF"
        ),
        LevelDef(
            id = "BIT",
            displayName = "1-Bit Register",
            chapter = ChapterId.MEMORY,
            description = "If load=1 store in, else keep. Out always holds value.",
            hint = "MUX + DFF: MUX picks new or old, DFF stores.",
            inputs = listOf("in", "load"),
            outputs = listOf("out"),
            targetChipId = "BIT",
            allowedChipIds = ChipLibrary.all.keys.filter { it != "CPU_8" && it != "REGISTER_8" && it != "ALU_8" }.toList(),
            optimalNands = 12,
            unlocksChipId = "BIT"
        ),
        LevelDef(
            id = "REG_8",
            displayName = "8-Bit Register",
            chapter = ChapterId.MEMORY,
            description = "8-bit register with load control. 8 BITs in parallel.",
            hint = "8x BIT sharing load signal.",
            inputs = (0..7).map { "in$it" } + listOf("load"),
            outputs = (0..7).map { "out$it" },
            targetChipId = "REG_8",
            allowedChipIds = ChipLibrary.all.keys.filter { it != "CPU_8" && it != "ALU_8" }.toList(),
            optimalNands = 96,
            unlocksChipId = "REG_8"
        ),
        // CPU
        LevelDef(
            id = "ALU_8",
            displayName = "ALU (8-bit)",
            chapter = ChapterId.CPU,
            description = "op 00=ADD 01=AND 10=OR 11=XOR, plus zero & carry flags",
            hint = "Mux between ADDER_8, AND, OR, XOR results using op bits.",
            inputs = (0..7).map { "a$it" } + (0..7).map { "b$it" } + listOf("op0","op1"),
            outputs = (0..7).map { "out$it" } + listOf("zero","carry"),
            targetChipId = "ALU_8",
            allowedChipIds = ChipLibrary.all.keys.filter { it != "CPU_8" }.toList(),
            optimalNands = 200,
            flavor = "The brain's calculator — does all logic & arithmetic.",
            unlocksChipId = "ALU_8"
        ),
        LevelDef(
            id = "CPU_8",
            displayName = "8-Bit CPU",
            chapter = ChapterId.CPU,
            description = "Simplified 8-bit CPU: fetch-decode-execute cycle",
            hint = "Combine ALU, Registers, MUX for data paths. This is the capstone!",
            inputs = (0..7).map { "in$it" } + listOf("clk", "reset"),
            outputs = (0..7).map { "out$it" } + (0..7).map { "addr$it" } + listOf("write"),
            targetChipId = "CPU_8",
            allowedChipIds = ChipLibrary.all.keys.toList(),
            optimalNands = 2000,
            flavor = "You started with NAND. You now have a computer. Well done.",
            unlocksChipId = "CPU_8"
        )
    )

    val chapters: List<Chapter> = listOf(
        Chapter(ChapterId.FOUNDATION, "Foundation", "From NAND build the basic gates", all.filter { it.chapter == ChapterId.FOUNDATION }.map { it.id }),
        Chapter(ChapterId.ROUTING, "Routing", "Select and steer signals", all.filter { it.chapter == ChapterId.ROUTING }.map { it.id }),
        Chapter(ChapterId.ARITH, "Arithmetic", "Make numbers add", all.filter { it.chapter == ChapterId.ARITH }.map { it.id }),
        Chapter(ChapterId.MEMORY, "Memory", "Give your circuit a memory", all.filter { it.chapter == ChapterId.MEMORY }.map { it.id }),
        Chapter(ChapterId.CPU, "CPU", "The final machine", all.filter { it.chapter == ChapterId.CPU }.map { it.id }),
    )

    val byId: Map<String, LevelDef> = all.associateBy { it.id }
    fun get(id: String): LevelDef = byId[id] ?: error("Unknown level $id")
}
