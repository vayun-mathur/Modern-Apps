package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Chips

internal val basicsLevels: List<LevelDef> = listOf(
    chipLevel(
        Chapter.BASICS, "not", Chips.NOT,
        LevelText(
            R.string.lg_level_not_title,
            R.string.lg_level_not_brief,
            R.string.lg_level_not_hint,
        ),
        title = RowTitles.not,
    ),
    chipLevel(
        Chapter.BASICS, "and", Chips.AND,
        LevelText(
            R.string.lg_level_and_title,
            R.string.lg_level_and_brief,
            R.string.lg_level_and_hint,
        ),
        title = RowTitles.infix("AND"),
    ),
    chipLevel(
        Chapter.BASICS, "or", Chips.OR,
        LevelText(
            R.string.lg_level_or_title,
            R.string.lg_level_or_brief,
            R.string.lg_level_or_hint,
        ),
        title = RowTitles.infix("OR"),
    ),
    chipLevel(
        Chapter.BASICS, "nor", Chips.NOR,
        LevelText(
            R.string.lg_level_nor_title,
            R.string.lg_level_nor_brief,
            R.string.lg_level_nor_hint,
        ),
        title = RowTitles.infix("NOR"),
    ),
    chipLevel(
        Chapter.BASICS, "xor", Chips.XOR,
        LevelText(
            R.string.lg_level_xor_title,
            R.string.lg_level_xor_brief,
            R.string.lg_level_xor_hint,
        ),
        title = RowTitles.infix("XOR"),
    ),
    chipLevel(
        Chapter.BASICS, "xnor", Chips.XNOR,
        LevelText(
            R.string.lg_level_xnor_title,
            R.string.lg_level_xnor_brief,
            R.string.lg_level_xnor_hint,
        ),
        title = RowTitles.infix("XNOR"),
    ),
)
