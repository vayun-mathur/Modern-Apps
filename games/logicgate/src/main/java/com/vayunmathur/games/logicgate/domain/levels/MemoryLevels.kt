package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.sim.Case
import com.vayunmathur.games.logicgate.domain.sim.TestSpec

private fun ticks(title: RowTitle, vararg rows: Pair<List<Int>, List<Int?>>) =
    TestSpec.Seq(rows.map { (i, o) -> Case(i, o, title(i, o)) })

internal val memoryLevels: List<LevelDef> = listOf(
    LevelDef(
        id = "sr_latch",
        chapter = Chapter.MEMORY,
        title = "SR latch",
        brief = "Memory from feedback. s sets q to 1, r resets it to 0, and with both low q holds. nq is always NOT q.",
        hint = "Two NORs, each one's output wired into the other's input.",
        inputs = listOf(Pin("s"), Pin("r")),
        outputs = listOf(Pin("q"), Pin("nq")),
        tests = ticks(
            RowTitles.srLatch,
            listOf(1, 0) to listOf(1, 0),
            listOf(0, 0) to listOf(1, 0),
            listOf(0, 1) to listOf(0, 1),
            listOf(0, 0) to listOf(0, 1),
            listOf(1, 0) to listOf(1, 0),
            listOf(0, 0) to listOf(1, 0),
            listOf(0, 1) to listOf(0, 1),
            listOf(0, 0) to listOf(0, 1),
        ),
        par = 4,
    ),
    LevelDef(
        id = "d_latch",
        chapter = Chapter.MEMORY,
        title = "D latch",
        brief = "While en is 1, q follows d. When en drops to 0, q holds its last value.",
        hint = "Build an SR latch and drive its set and reset from d and en.",
        inputs = listOf(Pin("d"), Pin("en")),
        outputs = listOf(Pin("q")),
        tests = ticks(
            RowTitles.dLatch,
            listOf(1, 1) to listOf(1),
            listOf(0, 0) to listOf(1),
            listOf(0, 1) to listOf(0),
            listOf(1, 0) to listOf(0),
            listOf(1, 1) to listOf(1),
            listOf(0, 0) to listOf(1),
            listOf(1, 0) to listOf(1),
            listOf(0, 1) to listOf(0),
            listOf(0, 0) to listOf(0),
        ),
        par = 4,
        unlocks = listOf(Chips.DFF, Chips.DFF4, Chips.DFF8),
    ),
    chipLevel(
        Chapter.MEMORY, "bit", Chips.BIT,
        LevelText(
            "1-bit register",
            "A DFF stores its input on every tick. Build one that only stores when load is 1; q shows the stored bit.",
            "Feed the DFF from a MUX choosing between d and the DFF's own output.",
        ),
        tests = TestGen.seqFromChip(
            Chips.BIT,
            RowTitles.bit,
            listOf(
                listOf(1, 1), listOf(0, 0), listOf(0, 0), listOf(0, 1), listOf(1, 0),
                listOf(1, 0), listOf(1, 1), listOf(0, 0), listOf(0, 1), listOf(1, 1),
            ),
        ),
    ),
    chipLevel(
        Chapter.MEMORY, "reg8", Chips.REG8,
        LevelText(
            "8-bit register",
            "The same, a byte wide.",
            "A MUX8b in front of a DFF8.",
        ),
        tests = TestGen.seqFromChip(
            Chips.REG8,
            RowTitles.reg8,
            listOf(
                listOf(0x5A, 1), listOf(0, 0), listOf(0xFF, 0), listOf(0xFF, 1), listOf(0x12, 0),
                listOf(0x80, 1), listOf(0, 0), listOf(0x01, 1), listOf(0, 0),
            ),
        ),
    ),
    chipLevel(
        Chapter.MEMORY, "pc4", Chips.PC4,
        LevelText(
            "Program counter",
            "Each tick: if load is 1, jump to v; otherwise if en is 1, count up by one (15 wraps to 0); " +
                "otherwise hold.",
            "An INC4 and two MUX4bs in front of a DFF4.",
        ),
        tests = TestGen.seqFromChip(
            Chips.PC4,
            RowTitles.pc4,
            listOf(
                listOf(1, 0, 0), listOf(1, 0, 0), listOf(0, 0, 0), listOf(1, 0, 0), listOf(0, 1, 9),
                listOf(1, 0, 0), listOf(1, 0, 0), listOf(1, 1, 15), listOf(1, 0, 0), listOf(1, 0, 0),
                listOf(0, 0, 3), listOf(0, 1, 4), listOf(0, 0, 0),
            ),
        ),
    ),
    chipLevel(
        Chapter.MEMORY, "ram4", Chips.RAM4,
        LevelText(
            "RAM",
            "Four bytes. dout always shows the byte at address a1 a0; when we is 1, that byte becomes din on the tick.",
            "A DEC2 picks which REG8 loads; MUX8bs pick which one is read.",
        ),
        unlocks = listOf(Chips.RAM4, Chips.RAM16),
        tests = TestGen.seqFromChip(
            Chips.RAM4,
            RowTitles.ram4,
            listOf(
                listOf(0, 0, 0x11, 1), listOf(0, 1, 0x22, 1), listOf(0, 0, 0, 0), listOf(0, 1, 0, 0),
                listOf(1, 0, 0x33, 0), listOf(1, 0, 0, 0), listOf(1, 1, 0x44, 1), listOf(1, 1, 0, 0),
                listOf(0, 0, 0x99, 0), listOf(1, 0, 0x55, 1), listOf(1, 0, 0, 0), listOf(0, 1, 0, 0),
            ),
        ),
    ),
)
