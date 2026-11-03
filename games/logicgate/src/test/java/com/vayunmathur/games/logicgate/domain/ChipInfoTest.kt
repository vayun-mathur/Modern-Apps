package com.vayunmathur.games.logicgate.domain

import com.vayunmathur.games.logicgate.domain.model.ChipInfo
import com.vayunmathur.games.logicgate.domain.model.Chips
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChipInfoTest {

    @Test
    fun everyChipHasABlurb() {
        for (chip in Chips.all) {
            assertTrue(ChipInfo.of(chip.id).orEmpty().isNotBlank(), "missing blurb for ${chip.id}")
        }
    }

    @Test
    fun everyKnownChipIdResolves() {
        // Guards against a blurb written for an id that no chip uses.
        for (id in listOf(
            Chips.NAND, Chips.HIGH, Chips.LOW, Chips.NOT, Chips.AND, Chips.OR, Chips.NOR, Chips.XOR, Chips.XNOR,
            Chips.MUX, Chips.DMUX, Chips.DEC2, Chips.SPLIT8, Chips.JOIN8, Chips.SPLIT4, Chips.JOIN4,
            Chips.NIB_SPLIT, Chips.NIB_JOIN, Chips.NOT8, Chips.AND8, Chips.OR8, Chips.XOR8, Chips.MUX8B, Chips.MUX4B,
            Chips.HALF, Chips.FULL, Chips.ADD4, Chips.ADD8, Chips.INC4, Chips.SUB8, Chips.ZERO8, Chips.ALU8,
            Chips.DFF, Chips.DFF4, Chips.DFF8, Chips.BIT, Chips.REG8, Chips.PC4, Chips.RAM4, Chips.RAM16,
            Chips.DECODER, Chips.CPU,
        )) {
            assertNotNull(Chips[id], id)
            assertNotNull(ChipInfo.of(id), id)
        }
    }
}
