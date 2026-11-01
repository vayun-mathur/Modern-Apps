package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.domain.model.Chips

internal val arithmeticLevels: List<LevelDef> = listOf(
    chipLevel(
        Chapter.ARITHMETIC, "half_adder", Chips.HALF,
        LevelText(
            "Half adder",
            "Add two bits. s is the sum bit and c the carry.",
            "s is XOR and c is AND — and the NAND(a, b) inside an XOR is already NOT c.",
        ),
        title = RowTitles.half,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "full_adder", Chips.FULL,
        LevelText(
            "Full adder",
            "Add three bits: a, b and a carry in.",
            "Two half adders and an OR work. Nine NANDs is the record.",
        ),
        title = RowTitles.full,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "add4", Chips.ADD4,
        LevelText(
            "4-bit adder",
            "Add two 4-bit numbers and a carry in.",
            "Chain four full adders, each carry out feeding the next carry in.",
        ),
        title = RowTitles.add,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "add8", Chips.ADD8,
        LevelText(
            "8-bit adder",
            "Add two bytes and a carry in.",
            "Two ADD4s, one per nibble.",
        ),
        title = RowTitles.add,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "inc4", Chips.INC4,
        LevelText(
            "Incrementer",
            "Output a + 1, wrapping 15 round to 0.",
            "Bit 0 always flips. Every other bit is a half adder fed by the carry from below.",
        ),
        title = RowTitles.inc,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "sub8", Chips.SUB8,
        LevelText(
            "Subtractor",
            "Output a − b, wrapping below zero.",
            "In two's complement −b is NOT b + 1. Feed the + 1 in as the carry.",
        ),
        title = RowTitles.sub,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "zero8", Chips.ZERO8,
        LevelText(
            "Zero test",
            "Output 1 when every bit of a is 0.",
            "OR all eight bits together, then invert.",
        ),
        title = RowTitles.zero,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "alu8", Chips.ALU8,
        LevelText(
            "ALU",
            "op1 op0 picks the operation: 00 add, 01 subtract, 10 AND, 11 pass b. z is 1 when o is 0.",
            "Subtracting is adding NOT b with carry 1: XOR b with op0 to invert it on demand.",
        ),
        title = RowTitles.alu,
    ),
)
