package com.vayunmathur.games.logicgate.domain.model

/** A named pin carrying a [width]-bit signal (1, 4 or 8). Signals are bit masks in an `Int`. */
data class Pin(val name: String, val width: Int = 1) {
    val mask: Int get() = (1 shl width) - 1
}

/** How a chip turns input signals into output signals. */
sealed interface Behavior {
    /** Outputs are a pure function of inputs. */
    class Comb(val eval: (IntArray) -> IntArray) : Behavior

    /**
     * Clocked state. [outputs] reads the current state (and, when [readsInputs], the inputs too —
     * an asynchronous memory read); [next] runs once per tick on the clock edge.
     */
    class Seq(
        val init: IntArray,
        val outputs: (state: IntArray, inputs: IntArray) -> IntArray,
        val next: (state: IntArray, inputs: IntArray) -> IntArray,
        val readsInputs: Boolean = false,
    ) : Behavior

    /**
     * A player-built chip. The simulator inlines [circuit] rather than running it as a black box;
     * chip input `k` feeds inner node [inputNodes]`[k]` and chip output `k` reads [outputNodes]`[k]`.
     */
    class Composite(val circuit: Circuit, val inputNodes: List<NodeId>, val outputNodes: List<NodeId>) : Behavior
}

/** A placeable chip: its pins, what it costs in NAND gates, and its trusted native behaviour. */
data class ChipSpec(
    val id: String,
    val inputs: List<Pin>,
    val outputs: List<Pin>,
    val nandCost: Int,
    val behavior: Behavior,
) {
    /** True when outputs depend only on stored state, so this chip breaks combinational loops. */
    val isStateOnly: Boolean get() = behavior is Behavior.Seq && !behavior.readsInputs
}
