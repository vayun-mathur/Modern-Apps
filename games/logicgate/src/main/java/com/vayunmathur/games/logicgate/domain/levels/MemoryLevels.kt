package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.R
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
        title = R.string.lg_level_sr_latch_title,
        brief = R.string.lg_level_sr_latch_brief,
        hint = R.string.lg_level_sr_latch_hint,
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
        title = R.string.lg_level_d_latch_title,
        brief = R.string.lg_level_d_latch_brief,
        hint = R.string.lg_level_d_latch_hint,
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
            R.string.lg_level_bit_title,
            R.string.lg_level_bit_brief,
            R.string.lg_level_bit_hint,
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
            R.string.lg_level_reg8_title,
            R.string.lg_level_reg8_brief,
            R.string.lg_level_reg8_hint,
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
            R.string.lg_level_pc4_title,
            R.string.lg_level_pc4_brief,
            R.string.lg_level_pc4_hint,
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
            R.string.lg_level_ram4_title,
            R.string.lg_level_ram4_brief,
            R.string.lg_level_ram4_hint,
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
