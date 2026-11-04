package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Pin

/** Splitters and joiners arrive with the first bus level and stay for the rest of the game. */
internal val busGifts = listOf(Chips.NIB_SPLIT, Chips.NIB_JOIN, Chips.SPLIT8, Chips.JOIN8, Chips.SPLIT4, Chips.JOIN4)

/** The bus-wide gates handed over for solving the bus MUX. */
internal val bitwisePack = listOf(Chips.NOT8, Chips.AND8, Chips.OR8, Chips.XOR8, Chips.MUX8B, Chips.MUX4B)

internal val routingLevels: List<LevelDef> = listOf(
    chipLevel(
        Chapter.ROUTING, "mux", Chips.MUX,
        LevelText(
            R.string.lg_level_mux_title,
            R.string.lg_level_mux_brief,
            R.string.lg_level_mux_hint,
        ),
        title = RowTitles.mux,
    ),
    chipLevel(
        Chapter.ROUTING, "dmux", Chips.DMUX,
        LevelText(
            R.string.lg_level_dmux_title,
            R.string.lg_level_dmux_brief,
            R.string.lg_level_dmux_hint,
        ),
        title = RowTitles.dmux,
    ),
    chipLevel(
        Chapter.ROUTING, "dec2", Chips.DEC2,
        LevelText(
            R.string.lg_level_dec2_title,
            R.string.lg_level_dec2_brief,
            R.string.lg_level_dec2_hint,
        ),
        title = RowTitles.dec2,
    ),
    LevelDef(
        id = "nibble_swap",
        chapter = Chapter.ROUTING,
        title = R.string.lg_level_nibble_swap_title,
        brief = R.string.lg_level_nibble_swap_brief,
        hint = R.string.lg_level_nibble_swap_hint,
        inputs = listOf(Pin("x", 8)),
        outputs = listOf(Pin("y", 8)),
        tests = TestGen.comb(listOf(Pin("x", 8)), seed = 10, RowTitles.nibbleSwap) {
            listOf(((it[0] shl 4) or (it[0] shr 4)) and 0xFF)
        },
        par = 0,
        gifts = busGifts,
    ),
    chipLevel(
        Chapter.ROUTING, "bus_mux", Chips.MUX8B,
        LevelText(
            R.string.lg_level_bus_mux_title,
            R.string.lg_level_bus_mux_brief,
            R.string.lg_level_bus_mux_hint,
        ),
        title = RowTitles.busMux,
        unlocks = bitwisePack,
    ),
)
