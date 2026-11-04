package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Chips

internal val arithmeticLevels: List<LevelDef> = listOf(
    chipLevel(
        Chapter.ARITHMETIC, "half_adder", Chips.HALF,
        LevelText(
            R.string.lg_level_half_adder_title,
            R.string.lg_level_half_adder_brief,
            R.string.lg_level_half_adder_hint,
        ),
        title = RowTitles.half,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "full_adder", Chips.FULL,
        LevelText(
            R.string.lg_level_full_adder_title,
            R.string.lg_level_full_adder_brief,
            R.string.lg_level_full_adder_hint,
        ),
        title = RowTitles.full,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "add4", Chips.ADD4,
        LevelText(
            R.string.lg_level_add4_title,
            R.string.lg_level_add4_brief,
            R.string.lg_level_add4_hint,
        ),
        title = RowTitles.add,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "add8", Chips.ADD8,
        LevelText(
            R.string.lg_level_add8_title,
            R.string.lg_level_add8_brief,
            R.string.lg_level_add8_hint,
        ),
        title = RowTitles.add,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "inc4", Chips.INC4,
        LevelText(
            R.string.lg_level_inc4_title,
            R.string.lg_level_inc4_brief,
            R.string.lg_level_inc4_hint,
        ),
        title = RowTitles.inc,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "sub8", Chips.SUB8,
        LevelText(
            R.string.lg_level_sub8_title,
            R.string.lg_level_sub8_brief,
            R.string.lg_level_sub8_hint,
        ),
        title = RowTitles.sub,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "zero8", Chips.ZERO8,
        LevelText(
            R.string.lg_level_zero8_title,
            R.string.lg_level_zero8_brief,
            R.string.lg_level_zero8_hint,
        ),
        title = RowTitles.zero,
    ),
    chipLevel(
        Chapter.ARITHMETIC, "alu8", Chips.ALU8,
        LevelText(
            R.string.lg_level_alu8_title,
            R.string.lg_level_alu8_brief,
            R.string.lg_level_alu8_hint,
        ),
        title = RowTitles.alu,
    ),
)
