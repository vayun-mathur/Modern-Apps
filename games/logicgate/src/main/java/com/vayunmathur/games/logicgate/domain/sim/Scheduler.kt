package com.vayunmathur.games.logicgate.domain.sim

/** Orders a dependency graph into strongly connected groups, sources first (Tarjan). */
internal object Scheduler {

    /**
     * Groups nodes `0 until n` (except [excluded]) so every group only depends on groups before
     * it. Each entry is `(members in index order, isCyclic)`.
     */
    fun groups(n: Int, edges: List<Pair<Int, Int>>, excluded: Set<Int>): List<Pair<IntArray, Boolean>> {
        val adj = Array(n) { mutableListOf<Int>() }
        val selfLoop = BooleanArray(n)
        for ((a, b) in edges) {
            if (a in excluded || b in excluded) continue
            if (a == b) selfLoop[a] = true else adj[a] += b
        }
        val tarjan = Tarjan(n, adj)
        for (v in 0 until n) if (v !in excluded && tarjan.index[v] < 0) tarjan.visit(v)
        return tarjan.sccs.asReversed().map { scc ->
            val sorted = scc.sorted().toIntArray()
            sorted to (sorted.size > 1 || selfLoop[sorted[0]])
        }
    }

    private class Tarjan(n: Int, val adj: Array<MutableList<Int>>) {
        val index = IntArray(n) { -1 }
        val low = IntArray(n)
        val onStack = BooleanArray(n)
        val stack = ArrayDeque<Int>()
        val sccs = mutableListOf<List<Int>>()
        var counter = 0

        fun visit(v: Int) {
            index[v] = counter
            low[v] = counter
            counter++
            stack.addLast(v)
            onStack[v] = true
            for (w in adj[v]) {
                if (index[w] < 0) {
                    visit(w)
                    low[v] = minOf(low[v], low[w])
                } else if (onStack[w]) {
                    low[v] = minOf(low[v], index[w])
                }
            }
            if (low[v] == index[v]) {
                val scc = mutableListOf<Int>()
                do {
                    val w = stack.removeLast()
                    onStack[w] = false
                    scc += w
                } while (w != v)
                sccs += scc
            }
        }
    }
}
