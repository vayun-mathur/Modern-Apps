package com.vayunmathur.games.logicgate.domain.sim

import com.vayunmathur.games.logicgate.domain.model.Behavior
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Circuit
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.model.LevelInput
import com.vayunmathur.games.logicgate.domain.model.LevelOutput
import com.vayunmathur.games.logicgate.domain.model.Node
import com.vayunmathur.games.logicgate.domain.model.NodeId
import com.vayunmathur.games.logicgate.domain.model.PinRef
import com.vayunmathur.games.logicgate.domain.model.PinSide
import com.vayunmathur.games.logicgate.domain.model.Wire

/**
 * A circuit with every player-built chip inlined. [owner] maps each inlined node to the top-level
 * gate it came from; [aliases] maps each top-level composite output pin to the flat pin driving it.
 */
internal class Flattened(
    val circuit: Circuit,
    val owner: Map<NodeId, NodeId>,
    val aliases: Map<PinRef, PinRef>,
)

/**
 * Inlines [Behavior.Composite] chips into their primitive gates so one simulator runs the whole
 * board cycle-accurately, including feedback that crosses component boundaries. Top-level nodes
 * keep their ids. A wire chased through ports into a loop with no gate on it reads 0.
 */
internal object Flattener {

    fun flatten(circuit: Circuit, catalog: (String) -> ChipSpec?): Flattened {
        val composite = circuit.nodes.any { it is Gate && catalog(it.chipId)?.behavior is Behavior.Composite }
        if (!composite) return Flattened(circuit, emptyMap(), emptyMap())
        return Builder(circuit, catalog).build()
    }

    private class Inst(
        val key: Int,
        val circuit: Circuit,
        val parent: Inst?,
        val gate: Gate?,
        val ports: Behavior.Composite?,
        val owner: NodeId?,
        val chain: Set<String>,
    )

    private class Builder(private val top: Circuit, private val catalog: (String) -> ChipSpec?) {
        private val insts = mutableListOf<Inst>()
        private val flatId = HashMap<Pair<Int, NodeId>, NodeId>()
        private val children = HashMap<Pair<Int, NodeId>, Inst>()
        private val nodes = mutableListOf<Node>()
        private val owner = HashMap<NodeId, NodeId>()
        private var next = maxOf(top.nextId, (top.nodes.maxOfOrNull { it.id.raw } ?: -1) + 1)

        fun build(): Flattened {
            val root = Inst(0, top, null, null, null, null, emptySet()).also { insts += it }
            collect(root)
            val wires = insts.flatMap { inst ->
                inst.circuit.wires.mapNotNull { w ->
                    val to = flatId[inst.key to w.to.node] ?: return@mapNotNull null
                    resolve(inst, w.from, HashSet())?.let { Wire(it, PinRef(to, PinSide.IN, w.to.index)) }
                }
            }
            val aliases = HashMap<PinRef, PinRef>()
            for (n in top.nodes) {
                val ports = children[0 to n.id]?.ports ?: continue
                for (k in ports.outputNodes.indices) {
                    val ref = PinRef(n.id, PinSide.OUT, k)
                    resolve(root, ref, HashSet())?.let { aliases[ref] = it }
                }
            }
            return Flattened(Circuit(nodes, wires, next), owner, aliases)
        }

        private fun collect(inst: Inst) {
            val nested = inst.parent != null
            for (n in inst.circuit.nodes) {
                when (n) {
                    is LevelInput, is LevelOutput -> if (!nested) place(inst, n, n.id)
                    is Gate -> {
                        val spec = catalog(n.chipId) ?: continue
                        val ports = spec.behavior as? Behavior.Composite
                        when {
                            ports == null -> place(inst, n, if (nested) NodeId(next++) else n.id)
                            n.chipId in inst.chain || inst.chain.size >= MAX_DEPTH -> Unit
                            else -> {
                                val child = Inst(
                                    insts.size, ports.circuit, inst, n, ports,
                                    inst.owner ?: n.id, inst.chain + n.chipId,
                                )
                                insts += child
                                children[inst.key to n.id] = child
                                collect(child)
                            }
                        }
                    }
                }
            }
        }

        private fun place(inst: Inst, n: Node, id: NodeId) {
            flatId[inst.key to n.id] = id
            nodes += when (n) {
                is Gate -> n.copy(id = id)
                else -> n
            }
            inst.owner?.let { owner[id] = it }
        }

        /** The flat output pin that ultimately drives [ref], an output pin in [inst]'s circuit. */
        private fun resolve(inst: Inst, ref: PinRef, seen: MutableSet<Triple<Int, NodeId, Int>>): PinRef? {
            if (!seen.add(Triple(inst.key, ref.node, ref.index))) return null
            val flat = flatId[inst.key to ref.node]
            val child = children[inst.key to ref.node]
            val parent = inst.parent
            return when {
                flat != null -> PinRef(flat, PinSide.OUT, ref.index)
                child != null -> driverOfOutput(child, ref.index)?.let { resolve(child, it, seen) }
                parent != null -> driverOfInput(inst, parent, ref.node)?.let { resolve(parent, it, seen) }
                else -> null
            }
        }

        /** The pin inside [child] wired to its output port [k]. */
        private fun driverOfOutput(child: Inst, k: Int): PinRef? {
            val port = child.ports?.outputNodes?.getOrNull(k) ?: return null
            return child.circuit.wireInto(PinRef(port, PinSide.IN, 0))?.from
        }

        /** The pin in [parent] wired to the chip input that [inst]'s input node [node] stands for. */
        private fun driverOfInput(inst: Inst, parent: Inst, node: NodeId): PinRef? {
            val gate = inst.gate ?: return null
            val k = inst.ports?.inputNodes?.indexOf(node)?.takeIf { it >= 0 } ?: return null
            return parent.circuit.wireInto(PinRef(gate.id, PinSide.IN, k))?.from
        }
    }

    private const val MAX_DEPTH = 32
}
