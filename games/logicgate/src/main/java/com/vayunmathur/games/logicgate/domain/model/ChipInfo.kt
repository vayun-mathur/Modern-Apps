package com.vayunmathur.games.logicgate.domain.model

import androidx.annotation.StringRes
import com.vayunmathur.games.logicgate.R

/**
 * Each chip's localized name (tray, canvas, sheets) and its plain-English explanation, shown when a
 * chip card in the tray is tapped. Every chip in [Chips.all] has an entry (guarded by a test).
 */
object ChipInfo {

    private class ChipText(@StringRes val label: Int, @StringRes val blurb: Int)

    private val texts = mapOf(
        Chips.NAND to ChipText(R.string.lg_chip_nand_label, R.string.lg_chip_nand_info),
        Chips.HIGH to ChipText(R.string.lg_chip_high_label, R.string.lg_chip_high_info),
        Chips.LOW to ChipText(R.string.lg_chip_low_label, R.string.lg_chip_low_info),
        Chips.NOT to ChipText(R.string.lg_chip_not_label, R.string.lg_chip_not_info),
        Chips.AND to ChipText(R.string.lg_chip_and_label, R.string.lg_chip_and_info),
        Chips.OR to ChipText(R.string.lg_chip_or_label, R.string.lg_chip_or_info),
        Chips.NOR to ChipText(R.string.lg_chip_nor_label, R.string.lg_chip_nor_info),
        Chips.XOR to ChipText(R.string.lg_chip_xor_label, R.string.lg_chip_xor_info),
        Chips.XNOR to ChipText(R.string.lg_chip_xnor_label, R.string.lg_chip_xnor_info),

        Chips.MUX to ChipText(R.string.lg_chip_mux_label, R.string.lg_chip_mux_info),
        Chips.DMUX to ChipText(R.string.lg_chip_dmux_label, R.string.lg_chip_dmux_info),
        Chips.DEC2 to ChipText(R.string.lg_chip_dec2_label, R.string.lg_chip_dec2_info),
        Chips.SPLIT8 to ChipText(R.string.lg_chip_split8_label, R.string.lg_chip_split8_info),
        Chips.JOIN8 to ChipText(R.string.lg_chip_join8_label, R.string.lg_chip_join8_info),
        Chips.SPLIT4 to ChipText(R.string.lg_chip_split4_label, R.string.lg_chip_split4_info),
        Chips.JOIN4 to ChipText(R.string.lg_chip_join4_label, R.string.lg_chip_join4_info),
        Chips.NIB_SPLIT to ChipText(R.string.lg_chip_nib_split_label, R.string.lg_chip_nib_split_info),
        Chips.NIB_JOIN to ChipText(R.string.lg_chip_nib_join_label, R.string.lg_chip_nib_join_info),
        Chips.NOT8 to ChipText(R.string.lg_chip_not8_label, R.string.lg_chip_not8_info),
        Chips.AND8 to ChipText(R.string.lg_chip_and8_label, R.string.lg_chip_and8_info),
        Chips.OR8 to ChipText(R.string.lg_chip_or8_label, R.string.lg_chip_or8_info),
        Chips.XOR8 to ChipText(R.string.lg_chip_xor8_label, R.string.lg_chip_xor8_info),
        Chips.MUX8B to ChipText(R.string.lg_chip_mux8b_label, R.string.lg_chip_mux8b_info),
        Chips.MUX4B to ChipText(R.string.lg_chip_mux4b_label, R.string.lg_chip_mux4b_info),

        Chips.HALF to ChipText(R.string.lg_chip_half_label, R.string.lg_chip_half_info),
        Chips.FULL to ChipText(R.string.lg_chip_full_label, R.string.lg_chip_full_info),
        Chips.ADD4 to ChipText(R.string.lg_chip_add4_label, R.string.lg_chip_add4_info),
        Chips.ADD8 to ChipText(R.string.lg_chip_add8_label, R.string.lg_chip_add8_info),
        Chips.INC4 to ChipText(R.string.lg_chip_inc4_label, R.string.lg_chip_inc4_info),
        Chips.SUB8 to ChipText(R.string.lg_chip_sub8_label, R.string.lg_chip_sub8_info),
        Chips.ZERO8 to ChipText(R.string.lg_chip_zero8_label, R.string.lg_chip_zero8_info),
        Chips.ALU8 to ChipText(R.string.lg_chip_alu8_label, R.string.lg_chip_alu8_info),

        Chips.DFF to ChipText(R.string.lg_chip_dff_label, R.string.lg_chip_dff_info),
        Chips.DFF4 to ChipText(R.string.lg_chip_dff4_label, R.string.lg_chip_dff4_info),
        Chips.DFF8 to ChipText(R.string.lg_chip_dff8_label, R.string.lg_chip_dff8_info),
        Chips.BIT to ChipText(R.string.lg_chip_bit_label, R.string.lg_chip_bit_info),
        Chips.REG8 to ChipText(R.string.lg_chip_reg8_label, R.string.lg_chip_reg8_info),
        Chips.PC4 to ChipText(R.string.lg_chip_pc4_label, R.string.lg_chip_pc4_info),
        Chips.RAM4 to ChipText(R.string.lg_chip_ram4_label, R.string.lg_chip_ram4_info),
        Chips.RAM16 to ChipText(R.string.lg_chip_ram16_label, R.string.lg_chip_ram16_info),

        Chips.DECODER to ChipText(R.string.lg_chip_decoder_label, R.string.lg_chip_decoder_info),
        Chips.CPU to ChipText(R.string.lg_chip_cpu_label, R.string.lg_chip_cpu_info),
    )

    /** The name of [chipId] as a string resource, or null for a chip with no entry. */
    @StringRes
    fun label(chipId: String): Int? = texts[chipId]?.label

    /** The explanation of [chipId] as a string resource, or null for a chip with no entry. */
    @StringRes
    fun blurb(chipId: String): Int? = texts[chipId]?.blurb
}
