package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Isa
import com.vayunmathur.games.logicgate.domain.model.Isa.encode
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.sim.Case
import com.vayunmathur.games.logicgate.domain.sim.ProgramTest
import com.vayunmathur.games.logicgate.domain.sim.TestSpec

private fun program(name: String, maxTicks: Int, expectAcc: Int, vararg cells: Pair<Int, Int>): ProgramTest {
    val ram = MutableList(16) { 0 }
    for ((addr, value) in cells) ram[addr] = value
    return ProgramTest(name, ram, maxTicks, expectAcc)
}

/** Add two numbers, count down to zero with JZ, and multiply 3 × 4 by repeated addition. */
internal val cpuPrograms: List<ProgramTest> = listOf(
    program(
        "2 + 3 = 5", 16, 5,
        0 to encode(Isa.LDA, 14), 1 to encode(Isa.ADD, 15), 2 to encode(Isa.HLT),
        14 to 2, 15 to 3,
    ),
    program(
        "count 5 down to 0", 64, 0,
        0 to encode(Isa.LDI, 5), 1 to encode(Isa.SUB, 15), 2 to encode(Isa.JZ, 4), 3 to encode(Isa.JMP, 1),
        4 to encode(Isa.HLT),
        15 to 1,
    ),
    program(
        "3 × 4 = 12", 128, 12,
        0 to encode(Isa.LDA, 13), 1 to encode(Isa.ADD, 14), 2 to encode(Isa.STA, 13),
        3 to encode(Isa.LDA, 12), 4 to encode(Isa.SUB, 15), 5 to encode(Isa.STA, 12),
        6 to encode(Isa.JZ, 8), 7 to encode(Isa.JMP, 0), 8 to encode(Isa.LDA, 13), 9 to encode(Isa.HLT),
        12 to 4, 13 to 0, 14 to 3, 15 to 1,
    ),
)

private val decoderTests = TestSpec.Comb(
    (0..7).flatMap { op ->
        (0..1).map { z ->
            val lines = Isa.decode(op, z)
            val aluCare = lines[2] == 1
            val expected = lines.mapIndexed { i, v -> if (i < 2 && !aluCare) null else v }
            Case(listOf(op, z), expected, RowTitles.decoder(listOf(op, z), expected))
        }
    },
)

internal val cpuLevels: List<LevelDef> = listOf(
    chipLevel(
        Chapter.CPU, "decoder", Chips.DECODER,
        LevelText(
            R.string.lg_level_decoder_title,
            R.string.lg_level_decoder_brief,
            R.string.lg_level_decoder_hint,
        ),
        tests = decoderTests,
    ),
    chipLevel(
        Chapter.CPU, "cpu", Chips.CPU,
        LevelText(
            R.string.lg_level_cpu_title,
            R.string.lg_level_cpu_brief,
            R.string.lg_level_cpu_hint,
        ),
        tests = TestSpec.Programs(cpuPrograms, harness = true),
    ),
    LevelDef(
        id = "computer",
        chapter = Chapter.CPU,
        title = R.string.lg_level_computer_title,
        brief = R.string.lg_level_computer_brief,
        hint = R.string.lg_level_computer_hint,
        inputs = emptyList(),
        outputs = listOf(Pin("acc", 8), Pin("halt")),
        tests = TestSpec.Programs(cpuPrograms, harness = false),
        par = TestGen.chip(Chips.CPU).nandCost + TestGen.chip(Chips.RAM16).nandCost,
    ),
)
