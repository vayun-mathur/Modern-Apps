package com.vayunmathur.games.logicgate.domain.model

/**
 * The fixed 8-bit instruction set. An instruction is `op[4] | operand[4]`; one instruction runs
 * per tick because the dual-port RAM fetches the instruction and reads the operand together.
 */
object Isa {
    const val LDA = 0
    const val STA = 1
    const val ADD = 2
    const val SUB = 3
    const val LDI = 4
    const val JMP = 5
    const val JZ = 6
    const val HLT = 7

    private const val ALU_ADD = 0
    private const val ALU_SUB = 1
    private const val ALU_PASS_B = 3

    val decoderOutputs: List<Pin> = listOf(
        Pin("aluOp1"), Pin("aluOp0"), Pin("accLoad"), Pin("memWrite"), Pin("useImm"), Pin("jump"), Pin("halt"),
    )

    val cpuOutputs: List<Pin> = listOf(
        Pin("pc", 4), Pin("addr", 4), Pin("dout", 8), Pin("we"), Pin("acc", 8), Pin("halt"),
    )

    fun encode(op: Int, operand: Int = 0): Int = (op shl 4) or (operand and 0xF)

    /** Control lines for [op] (the top bit is ignored) given the accumulator-zero flag [z]. */
    fun decode(op: Int, z: Int): IntArray {
        val o = op and 7
        val aluOp = when (o) {
            LDA, LDI -> ALU_PASS_B
            SUB -> ALU_SUB
            else -> ALU_ADD
        }
        return intArrayOf(
            aluOp shr 1,
            aluOp and 1,
            bit(o == LDA || o == ADD || o == SUB || o == LDI),
            bit(o == STA),
            bit(o == LDI),
            bit(o == JMP || (o == JZ && z == 1)),
            bit(o == HLT),
        )
    }

    fun cpuOut(pc: Int, acc: Int, instr: Int): IntArray {
        val o = (instr shr 4) and 7
        return intArrayOf(pc, instr and 0xF, acc, bit(o == STA), acc, bit(o == HLT))
    }

    /** Next `[pc, acc]` after executing [instr] with [data] read from its operand address. */
    fun cpuNext(pc: Int, acc: Int, instr: Int, data: Int): IntArray {
        val c = decode(instr shr 4, bit(acc == 0))
        val operand = instr and 0xF
        val b = if (c[4] == 1) operand else data
        val newAcc = if (c[2] == 1) Chips.alu(acc, b, c[0] * 2 + c[1]) else acc
        return intArrayOf(Chips.pcNext(pc, c[6] xor 1, c[5], operand), newAcc)
    }

    private fun bit(b: Boolean) = if (b) 1 else 0
}
