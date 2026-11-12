package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.FULL_SUIT_SIZE
import com.vayunmathur.games.solitaire.data.SPIDER_TABLEAU_COUNT
import com.vayunmathur.games.solitaire.data.SpiderState
import com.vayunmathur.games.solitaire.data.TOTAL_SPIDER_SUITS

/** One player action in a Spider winning line, in the ViewModel's own vocabulary. */
sealed interface SpiderMove {
    /** Deal one stock row (needs every column non-empty). */
    data object Deal : SpiderMove

    /** Moves the same-suit run starting at [faceUpIndex] of column [from] onto [to]. */
    data class Move(val from: Int, val faceUpIndex: Int, val to: Int) : SpiderMove
}

/**
 * Spider solver for 1, 2 and 4 suits with the game's rules: any card may go on
 * one rank higher, only same-suit runs move, the stock deals a row only when no
 * column is empty, and a K-to-A same-suit run at a column's top clears itself.
 *
 * Pruned for speed (so a budget miss proves nothing): a run moves whole unless
 * it lands on its own suit, a run never slides sideways from one off-suit
 * parent to another, and only the first empty column is tried. Columns are
 * interchangeable once the stock is gone, so only then does the key fold
 * their order.
 */
class SpiderSolver private constructor(private val stock: Array<IntArray>) : SearchProblem {

    override fun isWon(state: ByteArray): Boolean = state[COMPLETED].toInt() >= TOTAL_SPIDER_SUITS

    override fun key(state: ByteArray): Long {
        val ordered = state[STOCK_LEFT].toInt() > 0
        var columns = 0L
        for (c in 0 until SPIDER_TABLEAU_COUNT) {
            val base = colBase(c)
            val h = mix64(hashBytes(state, base, state[base + 1] + 2, COLUMN_SEED))
            columns = if (ordered) mix64(columns xor h) else columns + h
        }
        return mix64(columns + state[STOCK_LEFT] * STOCK_MULT + state[COMPLETED])
    }

    override fun score(state: ByteArray): Int {
        var down = 0
        var breaks = 0
        var empty = 0
        for (c in 0 until SPIDER_TABLEAU_COUNT) {
            val base = colBase(c)
            val d = state[base].toInt()
            val len = state[base + 1].toInt()
            down += d
            if (len == 0) empty++
            for (i in d + 1 until len) breaks += linkCost(state[base + 1 + i].toInt(), state[base + 2 + i].toInt())
        }
        return 6 * down + 2 * breaks - 3 * empty - 2 * FULL_SUIT_SIZE * state[COMPLETED] + 2 * state[STOCK_LEFT]
    }

    override fun expand(state: ByteArray, sink: ChildSink) {
        val firstEmpty = (0 until SPIDER_TABLEAU_COUNT).firstOrNull { state[colBase(it) + 1].toInt() == 0 }
        val runLengths = IntArray(SPIDER_TABLEAU_COUNT) {
            if (state[colBase(it) + 1] > 0) naturalRunLength(state, it) else 0
        }
        // Deal deadlock: rows left, a hole, and every column is a single run, so
        // only splitting a run can fill the holes the deal needs filled.
        val mustSplit = firstEmpty != null && state[STOCK_LEFT] > 0 &&
            (0 until SPIDER_TABLEAU_COUNT).all { state[colBase(it) + 1].toInt() == runLengths[it] }
        for (from in 0 until SPIDER_TABLEAU_COUNT) {
            if (runLengths[from] == 0) continue
            for (to in 0 until SPIDER_TABLEAU_COUNT) {
                if (to != from && to != firstEmpty) emitRunOnto(state, from, runLengths[from], to, sink)
            }
            if (firstEmpty != null) emitRunToEmpty(state, from, runLengths[from], firstEmpty, mustSplit, sink)
        }
        if (firstEmpty == null && state[STOCK_LEFT] > 0) sink.child(dealt(state), pack(DEAL, 0, 0, 0))
    }

    private fun emitRunOnto(state: ByteArray, from: Int, runLen: Int, to: Int, sink: ChildSink) {
        val target = topCard(state, to)
        if (target < 0) return
        val base = colBase(from)
        val len = state[base + 1].toInt()
        val count = codeRank(target) - codeRank(state[base + 1 + len].toInt())
        if (count < 1 || count > runLen) return
        val start = len - count
        val sameSuit = codeSuit(state[base + 2 + start].toInt()) == codeSuit(target)
        if (!sameSuit && count < runLen) return
        if (!sameSuit && start > state[base].toInt() && sitsOnRankParent(state, from, start)) return
        moved(state, from, start, to)?.let { sink.child(it, pack(MOVE, from, start - state[base].toInt(), to)) }
    }

    /**
     * The whole natural run to an empty column, unless it already is the whole
     * column; when [mustSplit], any tail of a whole-column run instead.
     */
    private fun emitRunToEmpty(state: ByteArray, from: Int, runLen: Int, to: Int, mustSplit: Boolean, sink: ChildSink) {
        val base = colBase(from)
        val len = state[base + 1].toInt()
        val start = len - runLen
        val starts = when {
            start > 0 -> start..start
            mustSplit -> 1 until len
            else -> IntRange.EMPTY
        }
        for (s in starts) {
            moved(state, from, s, to)?.let { sink.child(it, pack(MOVE, from, s - state[base].toInt(), to)) }
        }
    }

    private fun sitsOnRankParent(state: ByteArray, from: Int, start: Int): Boolean {
        val base = colBase(from)
        return codeRank(state[base + 1 + start].toInt()) == codeRank(state[base + 2 + start].toInt()) + 1
    }

    private fun dealt(state: ByteArray): ByteArray {
        val child = state.copyOf()
        val row = stock[stock.size - state[STOCK_LEFT]]
        for (c in 0 until SPIDER_TABLEAU_COUNT) {
            if (c < row.size) push(child, c, row[c])
        }
        child[STOCK_LEFT]--
        clearCompletedRuns(child)
        return child
    }

    /** The move with flip and clearing; null if a column would outgrow the layout. */
    private fun moved(state: ByteArray, from: Int, start: Int, to: Int): ByteArray? {
        val fromBase = colBase(from)
        val len = state[fromBase + 1].toInt()
        if (state[colBase(to) + 1] + len - start > MAX_COLUMN) return null
        val child = state.copyOf()
        for (k in start until len) push(child, to, state[fromBase + 2 + k].toInt())
        child[fromBase + 1] = start.toByte()
        flip(child, from)
        clearCompletedRuns(child)
        return child
    }

    companion object {
        /** The budget the shipped tables were generated with. */
        val DEFAULT_BUDGET = SolverBudget(dfsNodes = 100_000, beamWidth = 300, beamNodes = 500_000)

        fun solve(state: SpiderState, budget: SolverBudget = DEFAULT_BUDGET): SolveResult {
            val stock = state.stockGroups.map { group -> group.map { it.code() }.toIntArray() }.toTypedArray()
            val s = ByteArray(SIZE)
            s[COMPLETED] = state.completedSuits.toByte()
            s[STOCK_LEFT] = stock.size.toByte()
            state.tableauPiles.forEachIndexed { c, pile ->
                val base = colBase(c)
                val cards = pile.faceDown + pile.faceUp
                s[base] = pile.faceDown.size.toByte()
                s[base + 1] = cards.size.toByte()
                cards.forEachIndexed { i, card -> s[base + 2 + i] = card.code().toByte() }
            }
            return portfolioSearch(SpiderSolver(stock), s, budget)
        }

        fun decode(moves: IntArray): List<SpiderMove> = moves.map { m ->
            if (m and TYPE_MASK == DEAL) {
                SpiderMove.Deal
            } else {
                SpiderMove.Move(
                    from = (m ushr A_SHIFT) and COLUMN_MASK,
                    faceUpIndex = (m ushr B_SHIFT) and INDEX_MASK,
                    to = (m ushr C_SHIFT) and COLUMN_MASK,
                )
            }
        }

        private fun colBase(c: Int) = COLUMNS + c * COLUMN_SPAN

        private fun topCard(s: ByteArray, c: Int): Int {
            val base = colBase(c)
            val len = s[base + 1].toInt()
            return if (len == 0) -1 else s[base + 1 + len].toInt()
        }

        /** 0 for a same-suit link, 1 for an off-suit rank link, 2 for a dead break. */
        private fun linkCost(parent: Int, child: Int): Int = when {
            codeRank(parent) != codeRank(child) + 1 -> 2
            codeSuit(parent) != codeSuit(child) -> 1
            else -> 0
        }

        private fun naturalRunLength(s: ByteArray, c: Int): Int {
            val base = colBase(c)
            val down = s[base].toInt()
            val len = s[base + 1].toInt()
            var run = 1
            while (len - run > down && isSameSuitLink(s, base, len - run)) {
                run++
            }
            return run
        }

        /** Whether the card at absolute index [i] (cards start at base + 2) sits on its same-suit parent. */
        private fun isSameSuitLink(s: ByteArray, base: Int, i: Int) =
            linkCost(s[base + 1 + i].toInt(), s[base + 2 + i].toInt()) == 0

        private fun push(s: ByteArray, c: Int, card: Int) {
            val base = colBase(c)
            val len = s[base + 1].toInt()
            s[base + 2 + len] = card.toByte()
            s[base + 1] = (len + 1).toByte()
        }

        private fun flip(s: ByteArray, c: Int) {
            val base = colBase(c)
            val len = s[base + 1].toInt()
            if (len > 0 && s[base].toInt() == len) s[base] = (len - 1).toByte()
        }

        /** The game's check: each column's top 13 face-up cards, once, then flip. */
        private fun clearCompletedRuns(s: ByteArray) {
            for (c in 0 until SPIDER_TABLEAU_COUNT) {
                val base = colBase(c)
                val down = s[base].toInt()
                val len = s[base + 1].toInt()
                if (len - down < FULL_SUIT_SIZE) continue
                val start = len - FULL_SUIT_SIZE
                val complete = codeRank(s[base + 2 + start].toInt()) == RANKS &&
                    (start + 1 until len).all { linkCost(s[base + 1 + it].toInt(), s[base + 2 + it].toInt()) == 0 }
                if (!complete) continue
                s[base + 1] = start.toByte()
                flip(s, c)
                s[COMPLETED]++
            }
        }

        private fun pack(type: Int, a: Int, b: Int, c: Int) =
            type or (a shl A_SHIFT) or (b shl B_SHIFT) or (c shl C_SHIFT)

        private const val MOVE = 0
        private const val DEAL = 1
        private const val TYPE_MASK = 1
        private const val A_SHIFT = 1
        private const val B_SHIFT = 5
        private const val C_SHIFT = 12
        private const val COLUMN_MASK = 15
        private const val INDEX_MASK = 127

        private const val COMPLETED = 0
        private const val STOCK_LEFT = 1
        private const val COLUMNS = 2
        private const val MAX_COLUMN = 64
        private const val COLUMN_SPAN = MAX_COLUMN + 2
        private const val SIZE = COLUMNS + SPIDER_TABLEAU_COUNT * COLUMN_SPAN

        private const val COLUMN_SEED = 0x6789_ABCDL
        private const val STOCK_MULT = 0x9E37_79B9L
    }
}

