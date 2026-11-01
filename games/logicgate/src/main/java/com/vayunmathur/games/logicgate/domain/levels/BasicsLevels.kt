package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.domain.model.Chips

internal val basicsLevels: List<LevelDef> = listOf(
    chipLevel(
        Chapter.BASICS, "not", Chips.NOT,
        LevelText(
            "NOT",
            "Everything here is built from NAND. Make o the opposite of a.",
            "A NAND with both inputs tied to the same wire inverts it.",
        ),
        title = RowTitles.not,
    ),
    chipLevel(
        Chapter.BASICS, "and", Chips.AND,
        LevelText(
            "AND",
            "Output 1 only when a and b are both 1.",
            "NAND is NOT-AND. Undo the NOT.",
        ),
        title = RowTitles.infix("AND"),
    ),
    chipLevel(
        Chapter.BASICS, "or", Chips.OR,
        LevelText(
            "OR",
            "Output 1 when either input is 1.",
            "De Morgan: a OR b is NAND(NOT a, NOT b).",
        ),
        title = RowTitles.infix("OR"),
    ),
    chipLevel(
        Chapter.BASICS, "nor", Chips.NOR,
        LevelText(
            "NOR",
            "Output 1 only when both inputs are 0.",
            "Invert an OR.",
        ),
        title = RowTitles.infix("NOR"),
    ),
    chipLevel(
        Chapter.BASICS, "xor", Chips.XOR,
        LevelText(
            "XOR",
            "Output 1 when the inputs differ.",
            "Four NANDs: NAND(a, b) goes into two more NANDs, one with a and one with b; NAND their outputs.",
        ),
        title = RowTitles.infix("XOR"),
    ),
    chipLevel(
        Chapter.BASICS, "xnor", Chips.XNOR,
        LevelText(
            "XNOR",
            "Output 1 when the inputs match.",
            "Invert an XOR.",
        ),
        title = RowTitles.infix("XNOR"),
    ),
)
