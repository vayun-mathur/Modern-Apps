package com.vayunmathur.games.logicgate.domain.model

/**
 * Every placeable chip: the built-ins plus the player's [customs]. Custom specs are resolved once
 * up front (so the catalog is immutable and safe to share with the verifier's thread); a component
 * that contains itself, directly or not, resolves to nothing and drops out like an unknown chip.
 */
class ChipCatalog(val customs: List<CustomChip> = emptyList()) {
    private val byId: Map<String, CustomChip> = customs.associateBy { it.id }
    private val specs: Map<String, ChipSpec>

    init {
        val resolved = HashMap<String, ChipSpec?>()
        val resolving = HashSet<String>()
        fun resolve(id: String, depth: Int = 0): ChipSpec? {
            Chips[id]?.let { return it }
            if (id in resolved) return resolved[id]
            // A deep containment chain from persisted components must not
            // overflow the stack at startup; Flattener caps at 32 too.
            if (depth >= MAX_DEPTH) return null
            val chip = byId[id] ?: return null
            if (!resolving.add(id)) return null
            val gates = chip.board.circuit.nodes.filterIsInstance<Gate>()
            val inner = gates.map { resolve(it.chipId, depth + 1) }
            resolving.remove(id)
            val cyclic = gates.zip(inner).any { (g, s) -> s == null && byId[g.chipId] != null }
            val spec = if (cyclic) null else chip.toSpec(inner.sumOf { it?.nandCost ?: 0 })
            resolved[id] = spec
            return spec
        }
        customs.forEach { resolve(it.id) }
        specs = resolved.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    operator fun get(id: String): ChipSpec? = Chips[id] ?: specs[id]

    fun custom(id: String): CustomChip? = byId[id]

    fun lookup(io: LevelIo): PinLookup = Chips.lookup(io, ::get)

    /** True when [id] is [target] or contains it at any depth. */
    fun uses(id: String, target: String): Boolean {
        val seen = HashSet<String>()
        fun visit(c: String, depth: Int): Boolean {
            if (c == target) return true
            if (depth >= MAX_DEPTH) return false
            if (!seen.add(c)) return false
            return byId[c]?.board?.circuit?.nodes?.any { it is Gate && visit(it.chipId, depth + 1) } == true
        }
        return visit(id, 0)
    }

    /** Built-ins only. */
    companion object {
        val Builtin = ChipCatalog()

        /** Matches Flattener's nesting cap. */
        const val MAX_DEPTH = 32
    }
}
