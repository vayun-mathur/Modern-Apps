package com.vayunmathur.games.logicgate.domain.model

/**
 * What the editor works on: a circuit plus the pins its input/output nodes carry. In a level the
 * pins are fixed; in the sandbox the player adds, removes and renames them.
 */
data class Board(val circuit: Circuit, val io: LevelIo) {

    /** Adds a [width]-bit input or output node at [pos] with the first unused default name. */
    fun addPin(input: Boolean, width: Int, pos: Point): Board {
        val pins = if (input) io.inputs else io.outputs
        val taken = pins.map { it.name }.toSet()
        val names = if (input) INPUT_NAMES else OUTPUT_NAMES
        val name = names.firstOrNull { it !in taken } ?: generateSequence(pins.size + 1) { it + 1 }
            .map { (if (input) "in" else "out") + it }
            .first { it !in taken }
        val pin = Pin(name, width)
        val id = NodeId(circuit.nextId)
        val node = if (input) LevelInput(id, io.inputs.size, pos) else LevelOutput(id, io.outputs.size, pos)
        val nextIo = if (input) io.copy(inputs = io.inputs + pin) else io.copy(outputs = io.outputs + pin)
        return Board(circuit.copy(nodes = circuit.nodes + node, nextId = circuit.nextId + 1), nextIo)
    }

    /** Removes an input or output node, its wires and its pin; later pins shift down one index. */
    fun removePin(id: NodeId): Board {
        val node = circuit.node(id)
        val (ins, outs) = when (node) {
            is LevelInput -> io.inputs.filterIndexed { i, _ -> i != node.index } to io.outputs
            is LevelOutput -> io.inputs to io.outputs.filterIndexed { i, _ -> i != node.index }
            else -> return this
        }
        val nodes = circuit.nodes.filterNot { it.id == id }.map { n ->
            when {
                node is LevelInput && n is LevelInput && n.index > node.index -> n.copy(index = n.index - 1)
                node is LevelOutput && n is LevelOutput && n.index > node.index -> n.copy(index = n.index - 1)
                else -> n
            }
        }
        val wires = circuit.wires.filterNot { it.from.node == id || it.to.node == id }
        return Board(circuit.copy(nodes = nodes, wires = wires), LevelIo(ins, outs))
    }

    /** Renames the pin behind an input or output node. Blank names are ignored. */
    fun renamePin(id: NodeId, name: String): Board {
        val trimmed = name.trim().take(MAX_PIN_NAME)
        if (trimmed.isEmpty()) return this
        return when (val node = circuit.node(id)) {
            is LevelInput -> copy(io = io.copy(inputs = io.inputs.rename(node.index, trimmed)))
            is LevelOutput -> copy(io = io.copy(outputs = io.outputs.rename(node.index, trimmed)))
            else -> this
        }
    }

    private fun List<Pin>.rename(index: Int, name: String) =
        mapIndexed { i, p -> if (i == index) p.copy(name = name) else p }

    /** Default pin names. */
    companion object {
        const val MAX_PIN_NAME = 8
        private val INPUT_NAMES = ('a'..'n').map { it.toString() }
        private val OUTPUT_NAMES = ('o'..'z').map { it.toString() }

        val Empty = Board(Circuit(), LevelIo(emptyList(), emptyList()))
    }
}
