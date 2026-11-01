package com.vayunmathur.games.logicgate.domain.levels

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
            "Instruction decoder",
            "Opcodes: 0 LDA · 1 STA · 2 ADD · 3 SUB · 4 LDI · 5 JMP · 6 JZ · 7 HLT. Drive the ALU op " +
                "(LDA and LDI pass b), load acc for LDA/ADD/SUB/LDI, write memory for STA, use the immediate " +
                "for LDI, jump for JMP (and JZ when z is 1), halt for HLT. op's top bit is unused.",
            "Split op, decode its low three bits into eight lines, then OR together the lines each control needs.",
        ),
        tests = decoderTests,
    ),
    chipLevel(
        Chapter.CPU, "cpu", Chips.CPU,
        LevelText(
            "CPU",
            "Each tick, instr is the byte at pc and data is the byte at addr. Run the instruction: update acc " +
                "and pc, and to store put acc on dout and raise we. Tested by running real programs.",
            "operand → addr and PC4 v · ALU(acc, data or operand) → REG8 acc · ZERO8(acc) → decoder z · " +
                "NOT halt → PC4 en.",
        ),
        tests = TestSpec.Programs(cpuPrograms, harness = true),
    ),
    LevelDef(
        id = "computer",
        chapter = Chapter.CPU,
        title = "Computer",
        brief = "Wire your CPU to a RAM16. Three programs get loaded into the RAM: add two numbers, count down, " +
            "and multiply 3 × 4.",
        hint = "pc → iaddr, addr → daddr, dout → din, we → we; instr and dout go back into the CPU.",
        inputs = emptyList(),
        outputs = listOf(Pin("acc", 8), Pin("halt")),
        tests = TestSpec.Programs(cpuPrograms, harness = false),
        par = TestGen.chip(Chips.CPU).nandCost + TestGen.chip(Chips.RAM16).nandCost,
    ),
)
