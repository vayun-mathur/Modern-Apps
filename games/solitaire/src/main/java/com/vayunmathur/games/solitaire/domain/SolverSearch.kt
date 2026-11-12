package com.vayunmathur.games.solitaire.domain

import java.util.PriorityQueue

/** How a budgeted solver run ended. */
enum class SolveStatus {
    /** A full winning line was found; the deal is proven solvable. */
    SOLVED,

    /** The (pruned) search space was exhausted without a win. */
    UNSOLVABLE,

    /** The node budget ran out first: nothing is proven either way. */
    UNKNOWN,
}

/**
 * Search limits for one solve: a depth-first pass of [dfsNodes] expanded
 * positions, then (if [beamNodes] > 0 and DFS proved nothing) a beam of
 * [beamWidth] for up to [beamNodes]. Tables are generated with each solver's
 * `DEFAULT_BUDGET`; re-verifying a table seed must use the same budget.
 */
data class SolverBudget(val dfsNodes: Int, val beamWidth: Int = 0, val beamNodes: Int = 0)

/**
 * A solver result. [moves] holds the winning line as packed per-mode move codes
 * (see each solver's `decode`); it is empty unless [status] is SOLVED.
 */
class SolveResult(val status: SolveStatus, val moves: IntArray, val nodes: Int)

/**
 * A solitaire position space for [orderedDepthFirstSearch]. States are flat byte arrays
 * whose layout each solver owns; children are fresh arrays, never aliases.
 */
interface SearchProblem {
    fun isWon(state: ByteArray): Boolean

    /** 64-bit key of the canonical position (symmetries folded). */
    fun key(state: ByteArray): Long

    /** Heuristic distance to a win; lower is explored first. */
    fun score(state: ByteArray): Int

    /** Emits every child worth exploring, each with its packed move code. */
    fun expand(state: ByteArray, sink: ChildSink)
}

/** Receives each child position a [SearchProblem] generates, with its packed move code. */
fun interface ChildSink {
    fun child(state: ByteArray, move: Int)
}

private class Level(val children: Array<ByteArray>, val moves: IntArray) {
    var next = 0
}

/**
 * Depth-first search over children ordered by [SearchProblem.score], with a
 * transposition set. Memory stays at the set plus one sorted child list per
 * level, which is what lets many solves run in parallel offline.
 * Deterministic: the same problem, start and [nodeBudget] always give the same
 * result, which is what lets offline-generated seed tables be re-verified by
 * tests. A key collision can only prune a branch (lose completeness), never
 * fake a win, because the returned line is a sequence of real moves from the
 * start position.
 */
internal fun orderedDepthFirstSearch(problem: SearchProblem, start: ByteArray, nodeBudget: Int): SolveResult {
    if (problem.isWon(start)) return SolveResult(SolveStatus.SOLVED, IntArray(0), 0)
    val seen = LongHashSet()
    seen.add(problem.key(start))
    val stack = ArrayList<Level>()
    stack.add(expandSorted(problem, start, seen))
    var expanded = 1
    while (stack.isNotEmpty()) {
        val level = stack[stack.size - 1]
        if (level.next >= level.children.size) {
            stack.removeAt(stack.size - 1)
            continue
        }
        val child = level.children[level.next++]
        if (problem.isWon(child)) return SolveResult(SolveStatus.SOLVED, pathOf(stack), expanded)
        if (expanded >= nodeBudget) return SolveResult(SolveStatus.UNKNOWN, IntArray(0), expanded)
        expanded++
        stack.add(expandSorted(problem, child, seen))
    }
    return SolveResult(SolveStatus.UNSOLVABLE, IntArray(0), expanded)
}

/** The move codes leading to the child each level last handed out. */
private fun pathOf(stack: List<Level>): IntArray = IntArray(stack.size) { stack[it].moves[stack[it].next - 1] }

private fun expandSorted(problem: SearchProblem, state: ByteArray, seen: LongHashSet): Level {
    val states = ArrayList<ByteArray>()
    val codes = ArrayList<Int>()
    val scores = ArrayList<Int>()
    problem.expand(state) { child, move ->
        if (!seen.add(problem.key(child))) return@expand
        states.add(child)
        codes.add(move)
        scores.add(problem.score(child))
    }
    // Stable sort by score keeps the problem's own generation order on ties.
    val order = states.indices.sortedBy { scores[it] }
    return Level(Array(order.size) { states[order[it]] }, IntArray(order.size) { codes[order[it]] })
}

private class Frontier(val priority: Int, val id: Int, val state: ByteArray)

private val FRONTIER_ORDER = Comparator<Frontier> { a, b ->
    // Lowest score first; among equals the newest node, so ties dive deeper.
    if (a.priority != b.priority) a.priority.compareTo(b.priority) else b.id.compareTo(a.id)
}

/**
 * Greedy best-first search with a transposition set: always expands the
 * best-scoring open position anywhere in the tree, so an early mistake is
 * abandoned as soon as a sibling line looks better. Costs memory for the open
 * set (bounded by [nodeBudget] times the branching factor), so it suits the
 * problems where depth-first recovers badly from early mistakes (Spider).
 * Deterministic for the same reasons as [orderedDepthFirstSearch].
 */
internal fun bestFirstSearch(problem: SearchProblem, start: ByteArray, nodeBudget: Int): SolveResult {
    if (problem.isWon(start)) return SolveResult(SolveStatus.SOLVED, IntArray(0), 0)
    val parents = GrowableIntArray()
    val moves = GrowableIntArray()
    val seen = LongHashSet()
    val open = PriorityQueue(FRONTIER_ORDER)
    seen.add(problem.key(start))
    parents.add(-1)
    moves.add(0)
    open.add(Frontier(problem.score(start), 0, start))
    var expanded = 0
    var wonId = -1
    while (open.isNotEmpty() && wonId < 0) {
        if (expanded >= nodeBudget) return SolveResult(SolveStatus.UNKNOWN, IntArray(0), expanded)
        val node = open.poll()
        expanded++
        problem.expand(node.state) { child, move ->
            if (wonId >= 0 || !seen.add(problem.key(child))) return@expand
            val id = parents.size
            parents.add(node.id)
            moves.add(move)
            if (problem.isWon(child)) wonId = id else open.add(Frontier(problem.score(child), id, child))
        }
    }
    if (wonId < 0) return SolveResult(SolveStatus.UNSOLVABLE, IntArray(0), expanded)
    val path = ArrayList<Int>()
    var id = wonId
    while (id > 0) {
        path.add(moves[id])
        id = parents[id]
    }
    return SolveResult(SolveStatus.SOLVED, path.asReversed().toIntArray(), expanded)
}

internal class GrowableIntArray {
    private var data = IntArray(INITIAL_CAPACITY)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}

/**
 * Beam search: keeps the [width] best-scoring new positions of each depth and
 * expands only those. It cannot prove a deal unsolvable (an empty beam is
 * UNKNOWN), but on Spider it recovers from early mistakes far better than a
 * depth-first search, because every surviving line competes at each depth.
 * Deterministic: ties keep generation order.
 */
internal fun beamSearch(problem: SearchProblem, start: ByteArray, width: Int, nodeBudget: Int): SolveResult {
    if (problem.isWon(start)) return SolveResult(SolveStatus.SOLVED, IntArray(0), 0)
    val seen = LongHashSet()
    seen.add(problem.key(start))
    // Per depth: each kept node's parent index in the previous depth and its move.
    val parents = ArrayList<IntArray>()
    val moves = ArrayList<IntArray>()
    var beam = listOf(start)
    var expanded = 0
    while (beam.isNotEmpty() && expanded < nodeBudget) {
        val next = ArrayList<BeamChild>()
        val layer = LongHashSet()
        for ((index, state) in beam.withIndex()) {
            expanded++
            var won: BeamChild? = null
            problem.expand(state) { child, move ->
                val key = problem.key(child)
                if (won != null || seen.contains(key) || !layer.add(key)) return@expand
                val entry = BeamChild(child, key, problem.score(child), index, move)
                if (problem.isWon(child)) won = entry else next.add(entry)
            }
            won?.let { return SolveResult(SolveStatus.SOLVED, beamPath(parents, moves, it), expanded) }
        }
        // Only kept positions are marked seen: a trimmed one may still be reached later.
        val kept = next.sortedBy { it.score }.take(width)
        kept.forEach { seen.add(it.key) }
        parents.add(IntArray(kept.size) { kept[it].parent })
        moves.add(IntArray(kept.size) { kept[it].move })
        beam = kept.map { it.state }
    }
    return SolveResult(SolveStatus.UNKNOWN, IntArray(0), expanded)
}

private class BeamChild(val state: ByteArray, val key: Long, val score: Int, val parent: Int, val move: Int)

private fun beamPath(parents: List<IntArray>, moves: List<IntArray>, won: BeamChild): IntArray {
    val path = ArrayList<Int>()
    path.add(won.move)
    var index = won.parent
    for (depth in parents.indices.reversed()) {
        path.add(moves[depth][index])
        index = parents[depth][index]
    }
    return path.asReversed().toIntArray()
}

/**
 * A fixed portfolio: [orderedDepthFirstSearch], then, if that proved nothing,
 * [beamSearch], with the limits in [budget]. DFS
 * wins quickly on easy deals and can prove a deal unsolvable; beam rescues the
 * deals where DFS sinks into a hopeless subtree. Deterministic.
 */
internal fun portfolioSearch(problem: SearchProblem, start: ByteArray, budget: SolverBudget): SolveResult {
    val dfs = orderedDepthFirstSearch(problem, start, budget.dfsNodes)
    if (dfs.status != SolveStatus.UNKNOWN || budget.beamNodes <= 0) return dfs
    val beam = beamSearch(problem, start, budget.beamWidth, budget.beamNodes)
    return SolveResult(beam.status, beam.moves, dfs.nodes + beam.nodes)
}

/** Open-addressing set of non-zero-mixed longs; far lighter than HashSet<Long>. */
internal class LongHashSet {
    private var table = LongArray(INITIAL_CAPACITY)
    private var hasZero = false
    private var count = 0

    /** Adds [value]; returns false when it was already present. */
    fun add(value: Long): Boolean {
        if (value == 0L) {
            if (hasZero) return false
            hasZero = true
            return true
        }
        if ((count + 1) * 2 > table.size) grow()
        return insert(table, value)
    }

    fun contains(value: Long): Boolean {
        if (value == 0L) return hasZero
        val mask = table.size - 1
        var i = (mix64(value) and mask.toLong()).toInt()
        while (true) {
            val existing = table[i]
            if (existing == 0L) return false
            if (existing == value) return true
            i = (i + 1) and mask
        }
    }

    private fun insert(into: LongArray, value: Long): Boolean {
        val mask = into.size - 1
        var i = (mix64(value) and mask.toLong()).toInt()
        while (true) {
            val existing = into[i]
            if (existing == 0L) {
                into[i] = value
                if (into === table) count++
                return true
            }
            if (existing == value) return false
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val old = table
        val next = LongArray(old.size * 2)
        for (v in old) if (v != 0L) insert(next, v)
        table = next
    }

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 12
    }
}

/** SplitMix64 finaliser: a strong 64-bit mixer for keys and hashing. */
internal fun mix64(value: Long): Long {
    var z = value + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

/** Order-dependent hash of `state[from until from + length]`. */
internal fun hashBytes(state: ByteArray, from: Int, length: Int, seed: Long): Long {
    var h = seed
    for (i in from until from + length) h = mix64(h xor (state[i].toLong() and 0xFF))
    return h
}
