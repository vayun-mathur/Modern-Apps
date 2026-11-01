package com.vayunmathur.games.logicgate.domain.model

/** Stable identity of a node within one circuit. */
@JvmInline
value class NodeId(val raw: Int)

/** Which edge of a node a pin sits on. */
enum class PinSide { IN, OUT }

/** One pin of one node. */
data class PinRef(val node: NodeId, val side: PinSide, val index: Int)

/** A position in world units. */
data class Point(val x: Float, val y: Float)

/** Something on the board: a chip, or one of the level's own inputs/outputs. */
sealed interface Node {
    val id: NodeId
    val pos: Point
    fun moved(to: Point): Node
}

/** A placed chip. */
data class Gate(override val id: NodeId, val chipId: String, override val pos: Point) : Node {
    override fun moved(to: Point) = copy(pos = to)
}

/** The level's input [index], drawn as a source on the left of the board. */
data class LevelInput(override val id: NodeId, val index: Int, override val pos: Point) : Node {
    override fun moved(to: Point) = copy(pos = to)
}

/** The level's output [index], drawn as a sink on the right of the board. */
data class LevelOutput(override val id: NodeId, val index: Int, override val pos: Point) : Node {
    override fun moved(to: Point) = copy(pos = to)
}

/** A connection from an output pin to an input pin. */
data class Wire(val from: PinRef, val to: PinRef)

/** The pins a node exposes. */
data class NodePins(val inputs: List<Pin>, val outputs: List<Pin>) {
    fun pin(side: PinSide, index: Int): Pin? =
        (if (side == PinSide.IN) inputs else outputs).getOrNull(index)
}

/** The pin layout of a level's board. */
data class LevelIo(val inputs: List<Pin>, val outputs: List<Pin>)

/** Resolves the pins of a node; null when the node's chip is unknown. */
fun interface PinLookup {
    fun pins(node: Node): NodePins?
}

/** Outcome of asking whether two pins may be wired together. */
sealed interface ConnectCheck {
    /** Allowed; [wire] is the normalised output-to-input connection. */
    data class Ok(val wire: Wire) : ConnectCheck

    /** Both ends are inputs, or both are outputs. */
    data class SameSide(val side: PinSide) : ConnectCheck

    /** The pins carry different bus widths. */
    data class WidthMismatch(val fromWidth: Int, val toWidth: Int) : ConnectCheck

    /** One end does not exist. */
    data object MissingPin : ConnectCheck
}

/** An immutable circuit. Every edit returns a new value, which is what makes undo trivial. */
data class Circuit(
    val nodes: List<Node> = emptyList(),
    val wires: List<Wire> = emptyList(),
    val nextId: Int = 0,
) {
    fun node(id: NodeId): Node? = nodes.firstOrNull { it.id == id }

    fun addGate(chipId: String, pos: Point): Pair<Circuit, NodeId> {
        val id = NodeId(nextId)
        return copy(nodes = nodes + Gate(id, chipId, pos), nextId = nextId + 1) to id
    }

    fun moveNode(id: NodeId, pos: Point): Circuit =
        copy(nodes = nodes.map { if (it.id == id) it.moved(pos) else it })

    /** Removes a gate and every wire touching it. Level inputs/outputs cannot be removed. */
    fun removeNode(id: NodeId): Circuit {
        if (node(id) !is Gate) return this
        return copy(
            nodes = nodes.filterNot { it.id == id },
            wires = wires.filterNot { it.from.node == id || it.to.node == id },
        )
    }

    fun removeWire(wire: Wire): Circuit = copy(wires = wires - wire)

    fun wireInto(pin: PinRef): Wire? = wires.firstOrNull { it.to == pin }

    fun pin(ref: PinRef, lookup: PinLookup): Pin? =
        node(ref.node)?.let { lookup.pins(it) }?.pin(ref.side, ref.index)

    fun check(a: PinRef, b: PinRef, lookup: PinLookup): ConnectCheck {
        if (a.side == b.side) return ConnectCheck.SameSide(a.side)
        val (from, to) = if (a.side == PinSide.OUT) a to b else b to a
        val fromPin = pin(from, lookup) ?: return ConnectCheck.MissingPin
        val toPin = pin(to, lookup) ?: return ConnectCheck.MissingPin
        if (fromPin.width != toPin.width) return ConnectCheck.WidthMismatch(fromPin.width, toPin.width)
        return ConnectCheck.Ok(Wire(from, to))
    }

    /** Wires [a] to [b] in whichever direction is valid, replacing any wire already on the input. */
    fun connect(a: PinRef, b: PinRef, lookup: PinLookup): Circuit? {
        val ok = check(a, b, lookup) as? ConnectCheck.Ok ?: return null
        return copy(wires = wires.filterNot { it.to == ok.wire.to } + ok.wire)
    }

    /**
     * Drops anything a stale save could carry that the editor would never create: unknown chips,
     * wires to missing pins or of mismatched width, and second wires into one input. Also makes
     * sure every level input/output node exists.
     */
    fun sanitized(io: LevelIo, lookup: PinLookup): Circuit {
        val withIo = withLevelIo(io)
        val keptNodes = withIo.nodes.filter { lookup.pins(it) != null }
        val base = withIo.copy(nodes = keptNodes)
        val seenInputs = HashSet<PinRef>()
        val keptWires = base.wires.filter { w ->
            base.check(w.from, w.to, lookup) == ConnectCheck.Ok(w) && seenInputs.add(w.to)
        }
        val maxId = keptNodes.maxOfOrNull { it.id.raw } ?: -1
        return base.copy(wires = keptWires, nextId = maxOf(base.nextId, maxId + 1))
    }

    private fun withLevelIo(io: LevelIo): Circuit {
        val inIdx = nodes.filterIsInstance<LevelInput>().map { it.index }.toSet()
        val outIdx = nodes.filterIsInstance<LevelOutput>().map { it.index }.toSet()
        val fresh = forLevel(io)
        var next = nextId
        val missing = fresh.nodes.filter {
            (it is LevelInput && it.index !in inIdx) || (it is LevelOutput && it.index !in outIdx)
        }.map { n ->
            val id = NodeId(next++)
            when (n) {
                is LevelInput -> n.copy(id = id)
                is LevelOutput -> n.copy(id = id)
                is Gate -> n
            }
        }
        val pruned = nodes.filterNot {
            (it is LevelInput && it.index >= io.inputs.size) || (it is LevelOutput && it.index >= io.outputs.size)
        }
        return copy(nodes = pruned + missing, nextId = next)
    }

    /** Board layout defaults. */
    companion object {
        const val IO_SPACING = 96f
        const val OUTPUT_X = 400f

        /** An empty board with the level's inputs down the left and outputs down the right. */
        fun forLevel(io: LevelIo): Circuit {
            var id = 0
            val ins = io.inputs.indices.map { i ->
                LevelInput(NodeId(id++), i, Point(0f, column(i, io.inputs.size)))
            }
            val outs = io.outputs.indices.map { i ->
                LevelOutput(NodeId(id++), i, Point(OUTPUT_X, column(i, io.outputs.size)))
            }
            return Circuit(nodes = ins + outs, nextId = id)
        }

        private fun column(i: Int, n: Int) = (i - (n - 1) / 2f) * IO_SPACING
    }
}
