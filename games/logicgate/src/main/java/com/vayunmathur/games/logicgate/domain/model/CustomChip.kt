package com.vayunmathur.games.logicgate.domain.model

/**
 * A component the player built in the sandbox. Its pins are the board's inputs and outputs, in
 * top-to-bottom order (then left-to-right), so moving a pin node on the board reorders the chip.
 */
data class CustomChip(
    val id: String,
    val name: String,
    val group: String,
    val board: Board,
) {
    val inputNodes: List<LevelInput>
        get() = board.circuit.nodes.filterIsInstance<LevelInput>()
            .filter { it.index in board.io.inputs.indices }
            .sortedWith(BOARD_ORDER)

    val outputNodes: List<LevelOutput>
        get() = board.circuit.nodes.filterIsInstance<LevelOutput>()
            .filter { it.index in board.io.outputs.indices }
            .sortedWith(BOARD_ORDER)

    val inputs: List<Pin> get() = inputNodes.map { board.io.inputs[it.index] }
    val outputs: List<Pin> get() = outputNodes.map { board.io.outputs[it.index] }

    fun toSpec(nandCost: Int): ChipSpec = ChipSpec(
        id = id,
        inputs = inputs,
        outputs = outputs,
        nandCost = nandCost,
        behavior = Behavior.Composite(board.circuit, inputNodes.map { it.id }, outputNodes.map { it.id }),
    )

    /** Ids and naming. */
    companion object {
        const val PREFIX = "custom:"
        const val DEFAULT_GROUP = "My components"

        fun isCustom(chipId: String): Boolean = chipId.startsWith(PREFIX)

        private val BOARD_ORDER = compareBy<Node>({ it.pos.y }, { it.pos.x })
    }
}

/**
 * How [old]'s chip pins moved in [new]: for each old input/output position, its new position or
 * -1 when the pin is gone. Null when nothing moved.
 */
fun pinRemap(old: CustomChip, new: CustomChip): Pair<IntArray, IntArray>? {
    fun map(before: List<Node>, after: List<Node>): IntArray {
        val pos = after.withIndex().associate { (i, n) -> n.id to i }
        return IntArray(before.size) { pos[before[it].id] ?: -1 }
    }
    val ins = map(old.inputNodes, new.inputNodes)
    val outs = map(old.outputNodes, new.outputNodes)
    val identity = ins.withIndex().all { (i, v) -> i == v } && outs.withIndex().all { (i, v) -> i == v }
    return if (identity) null else ins to outs
}

/**
 * Rewires every [chipId] gate after its pins moved: [inMap]/[outMap] give each old pin index its
 * new one (-1 drops the wire).
 */
fun Circuit.remapChipPins(chipId: String, inMap: IntArray, outMap: IntArray): Circuit {
    val gates = nodes.filter { it is Gate && it.chipId == chipId }.map { it.id }.toSet()
    if (gates.isEmpty()) return this
    val rewired = wires.mapNotNull { w ->
        val from = if (w.from.node in gates) w.from.copy(index = outMap.getOrElse(w.from.index) { -1 }) else w.from
        val to = if (w.to.node in gates) w.to.copy(index = inMap.getOrElse(w.to.index) { -1 }) else w.to
        if (from.index < 0 || to.index < 0) null else Wire(from, to)
    }
    return copy(wires = rewired)
}

/** The sandbox tray's pin nodes: inputs and outputs of each bus width, placed like chips. */
object PinChips {
    private const val IN = "pin:in:"
    private const val OUT = "pin:out:"
    private val widths = listOf(1, 4, 8)

    val all: List<ChipSpec> =
        widths.map { spec(IN + it, emptyList(), listOf(Pin("in", it))) } +
            widths.map { spec(OUT + it, listOf(Pin("out", it)), emptyList()) }

    fun isPin(chipId: String): Boolean = chipId.startsWith(IN) || chipId.startsWith(OUT)

    fun isInput(chipId: String): Boolean = chipId.startsWith(IN)

    fun width(chipId: String): Int = chipId.substringAfterLast(':').toIntOrNull() ?: 1

    private fun spec(id: String, inputs: List<Pin>, outputs: List<Pin>) =
        ChipSpec(id, inputs, outputs, 0, Behavior.Comb { IntArray(outputs.size) })
}
