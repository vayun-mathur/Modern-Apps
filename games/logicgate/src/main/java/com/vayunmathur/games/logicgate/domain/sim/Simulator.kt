package com.vayunmathur.games.logicgate.domain.sim

import com.vayunmathur.games.logicgate.domain.model.Behavior
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.LevelIo
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.NodePins
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide

/**
 * Cycle-accurate simulator. One [step] is one tick of an implicit global clock:
 *
 * 1. [settle] combinational logic. Acyclic parts run once in topological order. Feedback loops
 *    iterate in-order (Gauss-Seidel) from the previous tick's values, which is what lets a
 *    cross-coupled latch pick a side and hold it; a loop still changing after [MAX_PASSES]
 *    passes is reported as oscillating.
 * 2. [clock] every sequential chip into its next state.
 *
 * State-only chips (registers) publish their outputs before anything else settles, so they break
 * every loop that runs through them. Unconnected inputs read 0.
 */
class Simulator(
    circuit: Circuit,
    io: LevelIo,
    catalog: (String) -> ChipSpec? = Chips::get,
) {
    private val nodes = circuit.nodes
    private val specs: Array<ChipSpec?> = Array(nodes.size) { (nodes[it] as? Gate)?.let { g -> catalog(g.chipId) } }
    private val inPins: Array<List<Pin>>
    private val outPins: Array<List<Pin>>
    private val outBase: IntArray
    private val values: IntArray
    private val inSrc: Array<IntArray>
    private val scratch: Array<IntArray>
    private val states: Array<IntArray?> = arrayOfNulls(nodes.size)
    private val levelInputs = IntArray(io.inputs.size)
    private val outputNodes = IntArray(io.outputs.size) { -1 }
    private val stateOnly: IntArray
    private val groups: List<IntArray>
    private val cyclic: BooleanArray

    init {
        val lookup = Chips.lookup(io)
        val empty = NodePins(emptyList(), emptyList())
        val pins = nodes.mapIndexed { i, n ->
            val spec = specs[i]
            if (n is Gate) NodePins(spec?.inputs.orEmpty(), spec?.outputs.orEmpty()) else lookup.pins(n) ?: empty
        }
        inPins = Array(nodes.size) { pins[it].inputs }
        outPins = Array(nodes.size) { pins[it].outputs }
        outBase = IntArray(nodes.size)
        var slot = 0
        for (i in nodes.indices) {
            outBase[i] = slot
            slot += outPins[i].size
        }
        values = IntArray(slot)
        val index = HashMap<NodeId, Int>()
        nodes.forEachIndexed { i, n -> index[n.id] = i }
        inSrc = Array(nodes.size) { IntArray(inPins[it].size) { -1 } }
        for (w in circuit.wires) {
            val src = index[w.from.node] ?: continue
            val dst = index[w.to.node] ?: continue
            if (w.from.index !in outPins[src].indices || w.to.index !in inPins[dst].indices) continue
            inSrc[dst][w.to.index] = outBase[src] + w.from.index
        }
        scratch = Array(nodes.size) { IntArray(inPins[it].size) }
        nodes.forEachIndexed { i, n ->
            if (n is LevelOutput && n.index in outputNodes.indices) outputNodes[n.index] = i
        }
        stateOnly = nodes.indices.filter { specs[it]?.isStateOnly == true }.toIntArray()
        val order = Scheduler.groups(nodes.size, edges(index, circuit), stateOnly.toSet())
        groups = order.map { it.first }
        cyclic = BooleanArray(order.size) { order[it].second }
        reset()
    }

    private fun edges(index: Map<NodeId, Int>, circuit: Circuit): List<Pair<Int, Int>> =
        circuit.wires.mapNotNull { w ->
            val a = index[w.from.node] ?: return@mapNotNull null
            val b = index[w.to.node] ?: return@mapNotNull null
            a to b
        }

    fun reset() {
        values.fill(0)
        for (i in nodes.indices) {
            states[i] = (specs[i]?.behavior as? Behavior.Seq)?.init?.copyOf()
        }
    }

    fun setInputs(inputs: IntArray) {
        for (i in levelInputs.indices) levelInputs[i] = inputs.getOrElse(i) { 0 }
    }

    /** Settles combinational logic; returns the nodes of any loop that never stabilised. */
    fun settle(): Set<NodeId> {
        for (i in stateOnly) evaluate(i)
        val oscillating = LinkedHashSet<NodeId>()
        for (g in groups.indices) {
            val group = groups[g]
            if (!cyclic[g]) {
                evaluate(group[0])
                continue
            }
            var pass = 0
            var changed = true
            while (changed && pass < MAX_PASSES) {
                changed = false
                for (i in group) if (evaluate(i)) changed = true
                pass++
            }
            if (changed) {
                for (i in group) if (evaluate(i)) oscillating += nodes[i].id
            }
        }
        return oscillating
    }

    /** Level output values from the last [settle]. */
    fun outputs(): IntArray = IntArray(outputNodes.size) { o ->
        val node = outputNodes[o]
        if (node < 0) 0 else read(node, 0)
    }

    /** Latches every sequential chip from the settled values. */
    fun clock() {
        for (i in nodes.indices) {
            val seq = specs[i]?.behavior as? Behavior.Seq ?: continue
            states[i] = seq.next(states[i] ?: seq.init, gather(i))
        }
    }

    /** One full tick: drive [inputs], settle, read outputs, then clock. */
    fun step(inputs: IntArray): Step {
        setInputs(inputs)
        val osc = settle()
        val out = outputs()
        clock()
        return Step(out, osc)
    }

    /** Overwrites the state of every [chipId] chip, e.g. to load a program into RAM. */
    fun preload(chipId: String, data: IntArray) {
        for (i in nodes.indices) {
            if (specs[i]?.id == chipId) states[i] = data.copyOf()
        }
    }

    fun stateOf(id: NodeId): IntArray? = nodes.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { states[it] }

    /** Current value on every output pin, which is also the value of every wire leaving it. */
    fun pinValues(): Map<PinRef, Int> {
        val map = HashMap<PinRef, Int>(values.size)
        for (i in nodes.indices) {
            for (k in outPins[i].indices) map[PinRef(nodes[i].id, PinSide.OUT, k)] = values[outBase[i] + k]
        }
        return map
    }

    private fun read(node: Int, pin: Int): Int {
        val src = inSrc[node][pin]
        return if (src < 0) 0 else values[src] and inPins[node][pin].mask
    }

    private fun gather(i: Int): IntArray {
        val buf = scratch[i]
        for (k in buf.indices) buf[k] = read(i, k)
        return buf
    }

    private fun evaluate(i: Int): Boolean {
        val node = nodes[i]
        val out: IntArray = when (node) {
            is LevelInput -> intArrayOf(levelInputs.getOrElse(node.index) { 0 })
            is LevelOutput -> return false
            is Gate -> when (val b = specs[i]?.behavior ?: return false) {
                is Behavior.Comb -> b.eval(gather(i))
                is Behavior.Seq -> b.outputs(states[i] ?: b.init, gather(i))
            }
        }
        var changed = false
        val base = outBase[i]
        val pins = outPins[i]
        for (k in pins.indices) {
            val v = out.getOrElse(k) { 0 } and pins[k].mask
            if (values[base + k] != v) {
                values[base + k] = v
                changed = true
            }
        }
        return changed
    }

    /** Outputs of one tick, read after settling and before the clock edge. */
    class Step(val outputs: IntArray, val oscillating: Set<NodeId>)

    /** Simulator limits. */
    companion object {
        const val MAX_PASSES = 64
    }
}
