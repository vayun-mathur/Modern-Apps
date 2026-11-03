package com.vayunmathur.games.logicgate.domain.model

/**
 * One- or two-sentence explanations of each chip, shown when a chip card in the tray is tapped.
 * Plain English like the level briefs; every chip in [Chips.all] has an entry (guarded by a test).
 */
object ChipInfo {

    private val blurbs = mapOf(
        Chips.NAND to "The gate everything starts from: o is 0 only when a and b are both 1. " +
            "Every other chip in the game is built from NANDs alone.",
        Chips.HIGH to "No inputs; o is always 1.",
        Chips.LOW to "No inputs; o is always 0.",
        Chips.NOT to "o is the opposite of a: 1 becomes 0, 0 becomes 1.",
        Chips.AND to "o is 1 only when a and b are both 1.",
        Chips.OR to "o is 1 when a, b or both are 1.",
        Chips.NOR to "OR then NOT: o is 1 only when a and b are both 0. " +
            "Two NORs wired into each other make an SR latch.",
        Chips.XOR to "o is 1 when a and b differ.",
        Chips.XNOR to "o is 1 when a and b are equal.",

        Chips.MUX to "Picks one of two inputs: o copies a while s is 0, b while s is 1.",
        Chips.DMUX to "The reverse of a MUX: in goes to a while s is 0, to b while s is 1. " +
            "The other output stays 0.",
        Chips.DEC2 to "One-hot decoder: the 2-bit number s1 s0 selects exactly one of y0–y3 to be 1; " +
            "the rest stay 0.",
        Chips.SPLIT8 to "Splits an 8-bit bus into its eight bits, b0 (lowest) to b7 (highest).",
        Chips.JOIN8 to "Packs eight bits, b0 (lowest) to b7 (highest), into one 8-bit bus.",
        Chips.SPLIT4 to "Splits a 4-bit bus into its four bits, b0 (lowest) to b3 (highest).",
        Chips.JOIN4 to "Packs four bits, b0 (lowest) to b3 (highest), into one 4-bit bus.",
        Chips.NIB_SPLIT to "Splits a byte into its two halves: lo is the low 4 bits, hi the high 4.",
        Chips.NIB_JOIN to "Packs a low and a high 4-bit half into one byte.",
        Chips.NOT8 to "Flips all eight bits of a at once.",
        Chips.AND8 to "Bitwise AND on bytes: each output bit is 1 only where a and b both have a 1.",
        Chips.OR8 to "Bitwise OR on bytes: each output bit is 1 where a or b has a 1.",
        Chips.XOR8 to "Bitwise XOR on bytes: each output bit is 1 where a and b differ.",
        Chips.MUX8B to "A byte-wide MUX: o copies a while s is 0, b while s is 1.",
        Chips.MUX4B to "A 4-bit-wide MUX: o copies a while s is 0, b while s is 1.",

        Chips.HALF to "Half adder: s is the sum of a and b, c is the carry into the next column.",
        Chips.FULL to "Full adder: adds a + b + cin; s is the sum bit, cout the carry into the next column.",
        Chips.ADD4 to "Adds two 4-bit numbers and cin; o is the 4-bit sum, cout the carry out. " +
            "Overflow wraps around.",
        Chips.ADD8 to "Adds two bytes and cin; o is the sum, cout the carry out. Overflow wraps around.",
        Chips.INC4 to "Counts up: o is a + 1. 15 wraps back to 0.",
        Chips.SUB8 to "d is a − b, wrapping modulo 256.",
        Chips.ZERO8 to "z is 1 only when every bit of a is 0 — the flag a CPU needs for jumps.",
        Chips.ALU8 to "Arithmetic and logic in one chip: op1 op0 choose 00 = a + b, 01 = a − b, " +
            "10 = a AND b, 11 = b. z is 1 when the result is 0.",

        Chips.DFF to "The memory primitive. q always shows the stored bit, and every tick of the " +
            "clock stores whatever d is — so q is d delayed by one tick. It never reads d while " +
            "settling, which is what breaks feedback loops.",
        Chips.DFF4 to "Four DFFs in one chip: stores a 4-bit number every tick.",
        Chips.DFF8 to "Eight DFFs in one chip: stores a byte every tick.",
        Chips.BIT to "A 1-bit register: on the tick it stores d when load is 1, " +
            "and holds its bit when load is 0.",
        Chips.REG8 to "A byte register: on the tick it stores d when load is 1, " +
            "and holds its byte when load is 0.",
        Chips.PC4 to "Program counter: on each tick it jumps to v when load is 1, " +
            "else counts up when en is 1 (15 wraps to 0), else holds.",
        Chips.RAM4 to "Four bytes of memory. dout always shows the byte at address a1 a0; " +
            "on the tick, din replaces that byte when we is 1.",
        Chips.RAM16 to "Sixteen bytes with two read ports: instr shows the byte at iaddr, dout the " +
            "byte at daddr. On the tick, din is written to daddr when we is 1.",

        Chips.DECODER to "Instruction decoder: from the 4-bit opcode and the z flag it produces the " +
            "CPU's control signals — aluOp1 aluOp0, accLoad, memWrite, useImm, jump and halt.",
        Chips.CPU to "A complete processor. Each tick it executes the instruction on instr, driving " +
            "pc, addr, dout and we to talk to RAM and reading data back; acc is the accumulator and " +
            "halt goes 1 when the program ends.",
    )

    /** The explanation of [chipId], or null for a chip with no entry. */
    fun of(chipId: String): String? = blurbs[chipId]
}
