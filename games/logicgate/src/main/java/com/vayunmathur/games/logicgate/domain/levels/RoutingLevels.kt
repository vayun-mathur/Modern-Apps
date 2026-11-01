package com.vayunmathur.games.logicgate.domain.levels

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
            "MUX",
            "A selector: o is a when s is 0, and b when s is 1.",
            "AND a with NOT s and b with s, then OR the two. In NANDs it is just four gates.",
        ),
        title = RowTitles.mux,
    ),
    chipLevel(
        Chapter.ROUTING, "dmux", Chips.DMUX,
        LevelText(
            "DMUX",
            "Send in to a when s is 0, or to b when s is 1. The other output stays 0.",
            "a = in AND NOT s, b = in AND s.",
        ),
        title = RowTitles.dmux,
    ),
    chipLevel(
        Chapter.ROUTING, "dec2", Chips.DEC2,
        LevelText(
            "Decoder",
            "Light exactly one of y0–y3: the one numbered by s1 s0 (s1 is the high bit).",
            "Each output is an AND of s1 or NOT s1 with s0 or NOT s0.",
        ),
        title = RowTitles.dec2,
    ),
    LevelDef(
        id = "nibble_swap",
        chapter = Chapter.ROUTING,
        title = "Nibble swap",
        brief = "Thick wires are buses carrying 4 or 8 bits at once. Swap the low and high halves of x.",
        hint = "NIB SPLIT gives the two halves; cross them into a NIB JOIN.",
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
            "Bus MUX",
            "Choose between two whole bytes. The one tedious bus level: solve it and every bus-wide gate is yours.",
            "Split both buses, use eight MUXes, and join the results.",
        ),
        title = RowTitles.busMux,
        unlocks = bitwisePack,
    ),
)
