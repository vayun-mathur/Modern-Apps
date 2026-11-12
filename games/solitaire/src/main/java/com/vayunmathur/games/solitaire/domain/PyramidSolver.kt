package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.PAIR_TARGET
import com.vayunmathur.games.solitaire.data.PYRAMID_ROWS
import com.vayunmathur.games.solitaire.data.PyramidState

/**
 * One player action in a Pyramid winning line. Ids are the board's own
 * (`pyr_<row>_<col>` or `waste`); a pair is replayed as two taps, [first] then
 * [second], and a lone King as one tap.
 */
sealed interface PyramidMove {
    /** Tap the stock: deal one card, or recycle the waste in relaxed mode. */
    data object Deal : PyramidMove

    /** Tap the exposed King [id] (pyramid card or waste top). */
    data class RemoveKing(val id: String) : PyramidMove

    /** Tap [first] (selects it), then [second] (completes the pair of 13). */
    data class RemovePair(val first: String, val second: String) : PyramidMove
}

/**
 * Pyramid solver over pair removals, Kings, and the stock/waste with the game's
 * pass rule (single pass, or unlimited recycles when relaxed). Includes the
 * game's sole-cover variation: an exposed card may pair with the card it is the
 * last cover of.
 *
 * The talon is modelled exactly: dealing preserves card order across recycles,
 * so the talon is a fixed sequence plus a removed mask and a position. The key
 * packs the whole position into 57 bits, so it is collision-free and an
 * exhausted search is a real proof of unsolvability.
 */
class PyramidSolver private constructor(
    private val pyramid: IntArray,
    private val talon: IntArray,
    private val relaxed: Boolean,
) : SearchProblem {

    override fun isWon(state: ByteArray): Boolean = (0 until PYRAMID_CARDS).none { state[it].toInt() != 0 }

    override fun key(state: ByteArray): Long {
        var k = 0L
        for (i in 0 until PYRAMID_CARDS + talon.size) k = (k shl 1) or state[i].toLong()
        return (k shl POS_BITS) or state[posIndex()].toLong()
    }

    override fun score(state: ByteArray): Int {
        var left = 0
        for (i in 0 until PYRAMID_CARDS) left += state[i]
        return left
    }

    override fun expand(state: ByteArray, sink: ChildSink) {
        val exposed = (0 until PYRAMID_CARDS).filter { isExposed(state, it) }
        for (i in exposed) if (rankAt(i) == KING) sink.child(removed(state, i, -1), pack(KING_MOVE, i, 0, 0))
        for (x in exposed.indices) {
            for (y in x + 1 until exposed.size) {
                val a = exposed[x]
                val b = exposed[y]
                if (rankAt(a) + rankAt(b) == PAIR_TARGET) sink.child(removed(state, a, b), pack(PAIR_MOVE, a, b, 0))
            }
            emitSoleCoverPairs(state, exposed[x], sink)
        }
        emitWastePlays(state, exposed, sink)
    }

    /**
     * Stock macro moves: every card that taps on the stock can bring to the
     * waste top, removed (a King) or paired with an exposed card in the same
     * edge. Taps that remove nothing are never needed, and pyramid-only moves
     * do not depend on the stock, so this loses no wins.
     */
    private fun emitWastePlays(state: ByteArray, exposed: List<Int>, sink: ChildSink) {
        val sim = state.copyOf()
        var taps = 0
        var recycled = false
        var seenCards = 0L
        while (true) {
            val top = wasteTop(sim)
            if (top >= 0 && (seenCards and (1L shl talon[top])) == 0L) {
                seenCards = seenCards or (1L shl talon[top])
                emitWasteRemovals(sim, talon[top], exposed, taps, sink)
            }
            val stockEmpty = nextInStock(sim) == null
            // A second recycle would only repeat the full pass after the first.
            if (stockEmpty && recycled) return
            val next = dealt(sim) ?: return
            recycled = recycled || stockEmpty
            next.copyInto(sim)
            taps++
        }
    }

    private fun emitWasteRemovals(atTop: ByteArray, card: Int, exposed: List<Int>, taps: Int, sink: ChildSink) {
        if (codeRank(card) == KING) {
            sink.child(removed(atTop, WASTE_SLOT, -1), pack(KING_MOVE, WASTE_SLOT, 0, taps))
            return
        }
        for (i in exposed) {
            if (rankAt(i) + codeRank(card) == PAIR_TARGET) {
                sink.child(removed(atTop, i, WASTE_SLOT), pack(PAIR_MOVE, i, WASTE_SLOT, taps))
            }
        }
    }

    /** [cover] with the card it alone still covers (the game's partial-cover rule). */
    private fun emitSoleCoverPairs(state: ByteArray, cover: Int, sink: ChildSink) {
        val (row, col) = rowCol(cover)
        if (row == 0) return
        // Parents of (row, col) are (row-1, col-1) and (row-1, col).
        for (pc in col - 1..col) {
            if (pc < 0 || pc > row - 1) continue
            val parent = slot(row - 1, pc)
            if (state[parent].toInt() == 0) continue
            val sibling = slot(row, if (pc == col) col + 1 else col - 1)
            if (state[sibling].toInt() != 0) continue
            if (rankAt(cover) + rankAt(parent) == PAIR_TARGET) {
                sink.child(removed(state, cover, parent), pack(PAIR_MOVE, cover, parent, 0))
            }
        }
    }

    private fun isExposed(state: ByteArray, i: Int): Boolean {
        if (state[i].toInt() == 0) return false
        val (row, col) = rowCol(i)
        if (row == PYRAMID_ROWS - 1) return true
        return state[slot(row + 1, col)].toInt() == 0 && state[slot(row + 1, col + 1)].toInt() == 0
    }

    private fun wasteTop(state: ByteArray): Int {
        val pos = state[posIndex()].toInt()
        for (t in pos - 1 downTo 0) if (state[PYRAMID_CARDS + t].toInt() != 0) return t
        return -1
    }

    private fun removed(state: ByteArray, a: Int, b: Int): ByteArray {
        val child = state.copyOf()
        for (slotId in intArrayOf(a, b)) {
            when {
                slotId == WASTE_SLOT -> child[PYRAMID_CARDS + wasteTop(state)] = 0
                slotId >= 0 -> child[slotId] = 0
            }
        }
        return child
    }

    private fun nextInStock(state: ByteArray): Int? =
        (state[posIndex()].toInt() until talon.size).firstOrNull { state[PYRAMID_CARDS + it].toInt() != 0 }

    /** The stock tap: deal the next card, or recycle when relaxed; null when it does nothing. */
    private fun dealt(state: ByteArray): ByteArray? {
        val next = nextInStock(state)
        val child = state.copyOf()
        when {
            next != null -> child[posIndex()] = (next + 1).toByte()
            relaxed && wasteTop(state) >= 0 -> child[posIndex()] = 0
            else -> return null
        }
        return child
    }

    private fun rankAt(i: Int) = codeRank(pyramid[i])

    private fun posIndex() = PYRAMID_CARDS + talon.size

    companion object {
        /** The budget the shipped tables were generated with. */
        val DEFAULT_BUDGET = SolverBudget(dfsNodes = 1_000_000)

        fun solve(state: PyramidState, budget: SolverBudget = DEFAULT_BUDGET): SolveResult {
            val pyramid = IntArray(PYRAMID_CARDS)
            val start = ByteArray(PYRAMID_CARDS + state.waste.size + state.stock.size + 1)
            for (r in 0 until PYRAMID_ROWS) {
                for (c in 0..r) {
                    val card = state.pyramid.getOrNull(r)?.getOrNull(c) ?: continue
                    pyramid[slot(r, c)] = card.code()
                    start[slot(r, c)] = 1
                }
            }
            // Draw order: the waste bottom-up, then the stock top-down. Dealing and
            // recycling both preserve it, so a position is just which are gone.
            val talon = (state.waste + state.stock.asReversed()).map { it.code() }.toIntArray()
            for (t in talon.indices) start[PYRAMID_CARDS + t] = 1
            start[PYRAMID_CARDS + talon.size] = state.waste.size.toByte()
            return portfolioSearch(PyramidSolver(pyramid, talon, state.relaxed), start, budget)
        }

        /** Expands packed codes into the stock taps and removals a player makes. */
        fun decode(moves: IntArray): List<PyramidMove> = buildList {
            for (m in moves) {
                repeat((m ushr TAPS_SHIFT) and TAPS_MASK) { add(PyramidMove.Deal) }
                val a = (m ushr A_SHIFT) and FIELD_MASK
                val b = (m ushr B_SHIFT) and FIELD_MASK
                val removal = if (m and TYPE_MASK == KING_MOVE) {
                    PyramidMove.RemoveKing(idOf(a))
                } else {
                    PyramidMove.RemovePair(idOf(a), idOf(b))
                }
                add(removal)
            }
        }

        private fun idOf(slotId: Int): String {
            if (slotId == WASTE_SLOT) return "waste"
            val (r, c) = rowCol(slotId)
            return "pyr_${r}_$c"
        }

        private fun slot(row: Int, col: Int) = row * (row + 1) / 2 + col

        private fun rowCol(i: Int): Pair<Int, Int> {
            var row = 0
            while (slot(row + 1, 0) <= i) row++
            return row to i - slot(row, 0)
        }

        private fun pack(type: Int, a: Int, b: Int, taps: Int) =
            type or (a shl A_SHIFT) or (b shl B_SHIFT) or (taps shl TAPS_SHIFT)

        private const val KING_MOVE = 1
        private const val PAIR_MOVE = 2
        private const val TYPE_MASK = 3
        private const val A_SHIFT = 2
        private const val B_SHIFT = 8
        private const val FIELD_MASK = 63
        private const val TAPS_SHIFT = 14
        private const val TAPS_MASK = 127
        private const val WASTE_SLOT = 63
        private const val KING = 13
        private const val POS_BITS = 5
        private const val PYRAMID_CARDS = 28
    }
}
