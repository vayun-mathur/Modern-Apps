package com.vayunmathur.games.logicgate.domain

import com.vayunmathur.games.logicgate.domain.levels.LevelDef
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Point

/** Builds circuits for tests: `g(chip, inputs...)` places a chip and wires its inputs in order. */
class Board(val level: LevelDef) {
    var circuit: Circuit = Circuit.forLevel(level.io)
        private set
    private val lookup = Chips.lookup(level.io)

    /** A placed chip. */
    inner class G(val id: NodeId) {
        fun out(i: Int = 0) = PinRef(id, PinSide.OUT, i)
        fun inp(i: Int) = PinRef(id, PinSide.IN, i)
    }

    fun input(i: Int): PinRef =
        PinRef(circuit.nodes.first { it is LevelInput && it.index == i }.id, PinSide.OUT, 0)

    fun output(i: Int): PinRef =
        PinRef(circuit.nodes.first { it is LevelOutput && it.index == i }.id, PinSide.IN, 0)

    fun wire(from: PinRef, to: PinRef) {
        circuit = requireNotNull(circuit.connect(from, to, lookup)) { "cannot wire $from -> $to in ${level.id}" }
    }

    fun g(chip: String, vararg ins: PinRef?): G {
        val (c, id) = circuit.addGate(chip, Point(120f + circuit.nodes.size * 8f, 0f))
        circuit = c
        val gate = G(id)
        ins.forEachIndexed { i, p -> if (p != null) wire(p, gate.inp(i)) }
        return gate
    }

    infix fun PinRef.into(to: PinRef) = wire(this, to)
}

/** A known-good solution for every level; each one's cost is that level's par. */
object ReferenceCircuits {

    fun build(levelId: String): Circuit {
        val level = requireNotNull(LevelRegistry[levelId])
        val board = Board(level)
        val recipe = requireNotNull(recipes[levelId]) { "no reference for $levelId" }
        board.recipe()
        return board.circuit
    }

    val levelIds: Set<String> get() = recipes.keys

    private val recipes: Map<String, Board.() -> Unit> = mapOf(
        "not" to { g(Chips.NAND, input(0), input(0)).out() into output(0) },
        "and" to { g(Chips.NOT, g(Chips.NAND, input(0), input(1)).out()).out() into output(0) },
        "or" to { g(Chips.NAND, g(Chips.NOT, input(0)).out(), g(Chips.NOT, input(1)).out()).out() into output(0) },
        "nor" to { g(Chips.NOT, g(Chips.OR, input(0), input(1)).out()).out() into output(0) },
        "xor" to { xorFromNands(input(0), input(1)) into output(0) },
        "xnor" to { g(Chips.NOT, g(Chips.XOR, input(0), input(1)).out()).out() into output(0) },
        "mux" to {
            val ns = g(Chips.NOT, input(2))
            g(Chips.NAND, g(Chips.NAND, input(0), ns.out()).out(), g(Chips.NAND, input(1), input(2)).out()).out() into
                output(0)
        },
        "dmux" to {
            val ns = g(Chips.NOT, input(1))
            g(Chips.AND, input(0), ns.out()).out() into output(0)
            g(Chips.AND, input(0), input(1)).out() into output(1)
        },
        "dec2" to {
            val n1 = g(Chips.NOT, input(0)).out()
            val n0 = g(Chips.NOT, input(1)).out()
            g(Chips.AND, n1, n0).out() into output(0)
            g(Chips.AND, n1, input(1)).out() into output(1)
            g(Chips.AND, input(0), n0).out() into output(2)
            g(Chips.AND, input(0), input(1)).out() into output(3)
        },
        "nibble_swap" to {
            val s = g(Chips.NIB_SPLIT, input(0))
            g(Chips.NIB_JOIN, s.out(1), s.out(0)).out() into output(0)
        },
        "bus_mux" to {
            val sa = g(Chips.SPLIT8, input(0))
            val sb = g(Chips.SPLIT8, input(1))
            val j = g(Chips.JOIN8)
            for (i in 0 until 8) g(Chips.MUX, sa.out(i), sb.out(i), input(2)).out() into j.inp(i)
            j.out() into output(0)
        },
        "half_adder" to {
            val n1 = g(Chips.NAND, input(0), input(1))
            val n2 = g(Chips.NAND, input(0), n1.out())
            val n3 = g(Chips.NAND, input(1), n1.out())
            g(Chips.NAND, n2.out(), n3.out()).out() into output(0)
            g(Chips.NOT, n1.out()).out() into output(1)
        },
        "full_adder" to {
            val n1 = g(Chips.NAND, input(0), input(1))
            val x = g(Chips.NAND, g(Chips.NAND, input(0), n1.out()).out(), g(Chips.NAND, input(1), n1.out()).out())
            val n5 = g(Chips.NAND, x.out(), input(2))
            g(Chips.NAND, g(Chips.NAND, x.out(), n5.out()).out(), g(Chips.NAND, input(2), n5.out()).out()).out() into
                output(0)
            g(Chips.NAND, n1.out(), n5.out()).out() into output(1)
        },
        "add4" to {
            val sa = g(Chips.SPLIT4, input(0))
            val sb = g(Chips.SPLIT4, input(1))
            val j = g(Chips.JOIN4)
            var carry = input(2)
            for (i in 0 until 4) {
                val f = g(Chips.FULL, sa.out(i), sb.out(i), carry)
                f.out(0) into j.inp(i)
                carry = f.out(1)
            }
            j.out() into output(0)
            carry into output(1)
        },
        "add8" to {
            val na = g(Chips.NIB_SPLIT, input(0))
            val nb = g(Chips.NIB_SPLIT, input(1))
            val lo = g(Chips.ADD4, na.out(0), nb.out(0), input(2))
            val hi = g(Chips.ADD4, na.out(1), nb.out(1), lo.out(1))
            g(Chips.NIB_JOIN, lo.out(0), hi.out(0)).out() into output(0)
            hi.out(1) into output(1)
        },
        "inc4" to {
            val s = g(Chips.SPLIT4, input(0))
            val j = g(Chips.JOIN4)
            g(Chips.NOT, s.out(0)).out() into j.inp(0)
            var carry = s.out(0)
            for (i in 1 until 4) {
                val h = g(Chips.HALF, s.out(i), carry)
                h.out(0) into j.inp(i)
                carry = h.out(1)
            }
            j.out() into output(0)
        },
        "sub8" to {
            g(Chips.ADD8, input(0), g(Chips.NOT8, input(1)).out(), g(Chips.HIGH).out()).out(0) into output(0)
        },
        "zero8" to {
            val s = g(Chips.SPLIT8, input(0))
            val pairs = (0 until 4).map { g(Chips.OR, s.out(2 * it), s.out(2 * it + 1)).out() }
            val any = g(
                Chips.OR,
                g(Chips.OR, pairs[0], pairs[1]).out(),
                g(Chips.OR, pairs[2], pairs[3]).out(),
            )
            g(Chips.NOT, any.out()).out() into output(0)
        },
        "alu8" to { alu() },
        "sr_latch" to {
            val q = g(Chips.NAND, g(Chips.NOT, input(0)).out())
            val nq = g(Chips.NAND, g(Chips.NOT, input(1)).out(), q.out())
            nq.out() into q.inp(1)
            q.out() into output(0)
            nq.out() into output(1)
        },
        "d_latch" to {
            val sp = g(Chips.NAND, input(0), input(1))
            val rp = g(Chips.NAND, sp.out(), input(1))
            val q = g(Chips.NAND, sp.out())
            val qb = g(Chips.NAND, rp.out(), q.out())
            qb.out() into q.inp(1)
            q.out() into output(0)
        },
        "bit" to { register(Chips.MUX, Chips.DFF) },
        "reg8" to { register(Chips.MUX8B, Chips.DFF8) },
        "pc4" to {
            val r = g(Chips.DFF4)
            val inc = g(Chips.INC4, r.out())
            val counted = g(Chips.MUX4B, r.out(), inc.out(), input(0))
            g(Chips.MUX4B, counted.out(), input(2), input(1)).out() into r.inp(0)
            r.out() into output(0)
        },
        "ram4" to {
            val dec = g(Chips.DEC2, input(0), input(1))
            val regs = (0 until 4).map { i -> g(Chips.REG8, input(2), g(Chips.AND, input(3), dec.out(i)).out()) }
            val m01 = g(Chips.MUX8B, regs[0].out(), regs[1].out(), input(1))
            val m23 = g(Chips.MUX8B, regs[2].out(), regs[3].out(), input(1))
            g(Chips.MUX8B, m01.out(), m23.out(), input(0)).out() into output(0)
        },
        "decoder" to { decoder() },
        "cpu" to { cpu() },
        "computer" to {
            val cpu = g(Chips.CPU)
            val ram = g(Chips.RAM16, cpu.out(0), cpu.out(1), cpu.out(2), cpu.out(3))
            ram.out(0) into cpu.inp(0)
            ram.out(1) into cpu.inp(1)
            cpu.out(4) into output(0)
            cpu.out(5) into output(1)
        },
    )

    private fun Board.xorFromNands(a: PinRef, b: PinRef): PinRef {
        val n1 = g(Chips.NAND, a, b)
        return g(Chips.NAND, g(Chips.NAND, a, n1.out()).out(), g(Chips.NAND, b, n1.out()).out()).out()
    }

    /** `q = DFF(MUX(q, d, load))`. */
    private fun Board.register(mux: String, dff: String) {
        val m = g(mux)
        val r = g(dff, m.out())
        r.out() into m.inp(0)
        input(0) into m.inp(1)
        input(1) into m.inp(2)
        r.out() into output(0)
    }

    private fun Board.alu() {
        val op1 = input(2)
        val op0 = input(3)
        val inv = g(Chips.JOIN8, op0, op0, op0, op0, op0, op0, op0, op0)
        val bx = g(Chips.XOR8, input(1), inv.out())
        val sum = g(Chips.ADD8, input(0), bx.out(), op0)
        val and = g(Chips.AND8, input(0), input(1))
        val logic = g(Chips.MUX8B, and.out(), input(1), op0)
        val o = g(Chips.MUX8B, sum.out(0), logic.out(), op1)
        o.out() into output(0)
        g(Chips.ZERO8, o.out()).out() into output(1)
    }

    private fun Board.decoder() {
        val s = g(Chips.SPLIT4, input(0))
        val (o0, o1, o2) = Triple(s.out(0), s.out(1), s.out(2))
        val dec = g(Chips.DEC2, o1, o0)
        val n2 = g(Chips.NOT, o2).out()
        val line = (0 until 8).map { k -> g(Chips.AND, dec.out(k and 3), if (k < 4) n2 else o2).out() }
        g(Chips.NOT, o1).out() into output(0)
        g(Chips.NAND, o1, g(Chips.NOT, o0).out()).out() into output(1)
        g(Chips.OR, g(Chips.OR, line[0], line[2]).out(), g(Chips.OR, line[3], line[4]).out()).out() into output(2)
        line[1] into output(3)
        line[4] into output(4)
        g(Chips.OR, line[5], g(Chips.AND, line[6], input(1)).out()).out() into output(5)
        line[7] into output(6)
    }

    private fun Board.cpu() {
        val instr = g(Chips.NIB_SPLIT, input(0))
        val operand = instr.out(0)
        val dec = g(Chips.DECODER, instr.out(1))
        val imm = g(Chips.NIB_JOIN, operand)
        val b = g(Chips.MUX8B, input(1), imm.out(), dec.out(4))
        val acc = g(Chips.REG8)
        val alu = g(Chips.ALU8, acc.out(), b.out(), dec.out(0), dec.out(1))
        alu.out(0) into acc.inp(0)
        dec.out(2) into acc.inp(1)
        g(Chips.ZERO8, acc.out()).out() into dec.inp(1)
        val pc = g(Chips.PC4, g(Chips.NOT, dec.out(6)).out(), dec.out(5), operand)
        pc.out() into output(0)
        operand into output(1)
        acc.out() into output(2)
        dec.out(3) into output(3)
        acc.out() into output(4)
        dec.out(6) into output(5)
    }
}
