package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.FREECELL_COUNT
import com.vayunmathur.games.solitaire.data.FREECELL_TABLEAU_COUNT
import com.vayunmathur.games.solitaire.data.FreeCellState

/** One player action in a FreeCell winning line, in the ViewModel's own vocabulary. */
sealed interface FreeCellMove {
    /** Top of column [column] to foundation [foundation] (foundations are suit-indexed). */
    data class TableauToFoundation(val column: Int, val foundation: Int) : FreeCellMove

    /** Free cell [cell] to foundation [foundation]. */
    data class FreeCellToFoundation(val cell: Int, val foundation: Int) : FreeCellMove

    /** Moves the run starting at absolute index [cardIndex] of [from] onto [to]. */
    data class TableauToTableau(val from: Int, val cardIndex: Int, val to: Int) : FreeCellMove

    /** Free cell [cell] onto column [column]. */
    data class FreeCellToTableau(val cell: Int, val column: Int) : FreeCellMove

    /** Top of column [column] into the empty free cell [cell]. */
    data class TableauToFreeCell(val column: Int, val cell: Int) : FreeCellMove
}

/**
 * FreeCell solver using the game's own supermove limit
 * (`(1 + free cells) * 2^empty columns`, the target column excluded).
 * Columns and cells are interchangeable, so the transposition key ignores their
 * order; a card is auto-played to its foundation when no opposite-colour card
 * could still need it.
 */
class FreeCellSolver private constructor() : SearchProblem {

    override fun isWon(state: ByteArray): Boolean = (0 until SUITS).all { state[it].toInt() == RANKS }

    override fun key(state: ByteArray): Long {
        var columns = 0L
        for (c in 0 until FREECELL_TABLEAU_COUNT) {
            val base = colBase(c)
            columns += mix64(hashBytes(state, base, state[base] + 1, COLUMN_SEED))
        }
        var cells = 0L
        for (i in 0 until FREECELL_COUNT) cells += mix64(state[CELLS + i] + CELL_SEED)
        return mix64(columns xor (cells * CELL_MULT))
    }

    override fun score(state: ByteArray): Int {
        var breaks = 0
        var empty = 0
        for (c in 0 until FREECELL_TABLEAU_COUNT) {
            val base = colBase(c)
            val len = state[base].toInt()
            if (len == 0) empty++
            for (i in 1 until len) {
                if (!isNaturalChild(state[base + i].toInt(), state[base + 1 + i].toInt())) breaks++
            }
        }
        val usedCells = (0 until FREECELL_COUNT).count { state[CELLS + it] >= 0 }
        return 3 * (FULL_DECK - foundationTotal(state)) + 2 * usedCells + 2 * breaks - 2 * empty
    }

    override fun expand(state: ByteArray, sink: ChildSink) {
        if (emitSafeAutoPlay(state, sink)) return
        emitToFoundation(state, sink)
        emitRuns(state, sink, toEmpty = false)
        emitCellToColumn(state, sink, toEmpty = false)
        emitRuns(state, sink, toEmpty = true)
        emitCellToColumn(state, sink, toEmpty = true)
        emitToCell(state, sink)
    }

    private fun emitSafeAutoPlay(state: ByteArray, sink: ChildSink): Boolean {
        for (c in 0 until FREECELL_TABLEAU_COUNT) {
            val top = topCard(state, c)
            if (top >= 0 && isSafeToFoundation(state, top)) {
                sink.child(columnToFoundation(state, c, top), pack(T2F, c, codeSuit(top), 0))
                return true
            }
        }
        for (i in 0 until FREECELL_COUNT) {
            val card = state[CELLS + i].toInt()
            if (card >= 0 && isSafeToFoundation(state, card)) {
                sink.child(cellToFoundation(state, i, card), pack(C2F, i, codeSuit(card), 0))
                return true
            }
        }
        return false
    }

    private fun emitToFoundation(state: ByteArray, sink: ChildSink) {
        for (c in 0 until FREECELL_TABLEAU_COUNT) {
            val top = topCard(state, c)
            if (top >= 0 && fitsFoundation(state, top)) {
                sink.child(columnToFoundation(state, c, top), pack(T2F, c, codeSuit(top), 0))
            }
        }
        for (i in 0 until FREECELL_COUNT) {
            val card = state[CELLS + i].toInt()
            if (card >= 0 && fitsFoundation(state, card)) {
                sink.child(cellToFoundation(state, i, card), pack(C2F, i, codeSuit(card), 0))
            }
        }
    }

    /**
     * Run moves. Onto a card only the whole natural run (from the first card that
     * does not sit on its natural parent) or, for that exact target, the part of
     * it that fits; into an empty column the longest movable run, unless it is
     * already the whole column.
     */
    private fun emitRuns(state: ByteArray, sink: ChildSink, toEmpty: Boolean) {
        for (from in 0 until FREECELL_TABLEAU_COUNT) {
            val base = colBase(from)
            val len = state[base].toInt()
            if (len == 0) continue
            val runLen = naturalRunLength(state, from)
            if (toEmpty) emitRunToEmpty(state, from, runLen, sink) else emitRunToCards(state, from, runLen, sink)
        }
    }

    private fun emitRunToCards(state: ByteArray, from: Int, runLen: Int, sink: ChildSink) {
        val base = colBase(from)
        val len = state[base].toInt()
        val topRank = codeRank(state[base + len].toInt())
        for (to in 0 until FREECELL_TABLEAU_COUNT) {
            val target = topCard(state, to)
            if (to == from || target < 0) continue
            val count = codeRank(target) - topRank
            if (count < 1 || count > runLen || count > maxMove(state, from, to)) continue
            val start = len - count
            val first = state[base + 1 + start].toInt()
            if (codeIsRed(first) == codeIsRed(target)) continue
            sink.child(moveRun(state, from, start, to), pack(T2T, from, start, to))
        }
    }

    private fun emitRunToEmpty(state: ByteArray, from: Int, runLen: Int, sink: ChildSink) {
        val len = state[colBase(from)].toInt()
        val to = (0 until FREECELL_TABLEAU_COUNT).firstOrNull { state[colBase(it)].toInt() == 0 } ?: return
        val count = minOf(runLen, maxMove(state, from, to))
        if (count == len) return
        val start = len - count
        sink.child(moveRun(state, from, start, to), pack(T2T, from, start, to))
    }

    private fun emitCellToColumn(state: ByteArray, sink: ChildSink, toEmpty: Boolean) {
        val firstEmpty = (0 until FREECELL_TABLEAU_COUNT).firstOrNull { state[colBase(it)].toInt() == 0 }
        for (i in 0 until FREECELL_COUNT) {
            val card = state[CELLS + i].toInt()
            if (card < 0) continue
            if (toEmpty) {
                if (firstEmpty != null) {
                    sink.child(cellToColumn(state, i, card, firstEmpty), pack(C2T, i, firstEmpty, 0))
                }
                continue
            }
            for (to in 0 until FREECELL_TABLEAU_COUNT) {
                val target = topCard(state, to)
                if (target >= 0 && isNaturalChild(target, card)) {
                    sink.child(cellToColumn(state, i, card, to), pack(C2T, i, to, 0))
                }
            }
        }
    }

    private fun emitToCell(state: ByteArray, sink: ChildSink) {
        val cell = (0 until FREECELL_COUNT).firstOrNull { state[CELLS + it] < 0 } ?: return
        for (c in 0 until FREECELL_TABLEAU_COUNT) {
            val top = topCard(state, c)
            if (top < 0) continue
            val child = state.copyOf()
            val base = colBase(c)
            child[base] = (child[base] - 1).toByte()
            child[CELLS + cell] = top.toByte()
            sink.child(child, pack(T2C, c, cell, 0))
        }
    }

    private fun isSafeToFoundation(state: ByteArray, card: Int): Boolean {
        if (!fitsFoundation(state, card)) return false
        val rank = codeRank(card)
        if (rank <= 2) return true
        val firstOpposite = if (codeIsRed(card)) 2 else 0
        return state[firstOpposite] >= rank - 1 && state[firstOpposite + 1] >= rank - 1
    }

    companion object {
        fun solve(state: FreeCellState, budget: SolverBudget = DEFAULT_BUDGET): SolveResult =
            portfolioSearch(FreeCellSolver(), encode(state), budget)

        /** The budget the shipped table was generated with. */
        val DEFAULT_BUDGET = SolverBudget(dfsNodes = 100_000, beamWidth = 500, beamNodes = 300_000)

        fun decode(moves: IntArray): List<FreeCellMove> = moves.map { m ->
            val a = (m ushr A_SHIFT) and FIELD_MASK
            val b = (m ushr B_SHIFT) and FIELD_MASK
            when (m and TYPE_MASK) {
                T2F -> FreeCellMove.TableauToFoundation(a, b)
                C2F -> FreeCellMove.FreeCellToFoundation(a, b)
                T2T -> FreeCellMove.TableauToTableau(a, b, (m ushr C_SHIFT) and FIELD_MASK)
                C2T -> FreeCellMove.FreeCellToTableau(a, b)
                else -> FreeCellMove.TableauToFreeCell(a, b)
            }
        }

        private fun encode(state: FreeCellState): ByteArray {
            val s = ByteArray(SIZE)
            for (f in 0 until SUITS) s[f] = state.foundations[f].size.toByte()
            for (i in 0 until FREECELL_COUNT) s[CELLS + i] = (state.freeCells[i]?.code() ?: -1).toByte()
            state.tableauPiles.forEachIndexed { c, pile ->
                val base = colBase(c)
                s[base] = pile.size.toByte()
                pile.forEachIndexed { i, card -> s[base + 1 + i] = card.code().toByte() }
            }
            return s
        }

        private fun colBase(c: Int) = COLUMNS + c * COLUMN_SPAN

        private fun foundationTotal(s: ByteArray) = s[0] + s[1] + s[2] + s[3]

        private fun topCard(s: ByteArray, c: Int): Int {
            val base = colBase(c)
            val len = s[base].toInt()
            return if (len == 0) -1 else s[base + len].toInt()
        }

        private fun fitsFoundation(s: ByteArray, card: Int) = s[codeSuit(card)].toInt() == codeRank(card) - 1

        private fun isNaturalChild(parent: Int, child: Int) =
            codeIsRed(parent) != codeIsRed(child) && codeRank(parent) == codeRank(child) + 1

        private fun naturalRunLength(s: ByteArray, c: Int): Int {
            val base = colBase(c)
            val len = s[base].toInt()
            var run = 1
            while (run < len && isNaturalChild(s[base + len - run].toInt(), s[base + len - run + 1].toInt())) run++
            return run
        }

        /** The ViewModel's limit: empty columns other than source and target double it. */
        private fun maxMove(s: ByteArray, from: Int, to: Int): Int {
            val freeCells = (0 until FREECELL_COUNT).count { s[CELLS + it] < 0 }
            val emptyColumns = (0 until FREECELL_TABLEAU_COUNT).count {
                it != from && it != to && s[colBase(it)].toInt() == 0
            }
            return (1 + freeCells) shl emptyColumns
        }

        private fun moveRun(s: ByteArray, from: Int, start: Int, to: Int): ByteArray {
            val child = s.copyOf()
            val fromBase = colBase(from)
            val toBase = colBase(to)
            val len = s[fromBase].toInt()
            var toLen = s[toBase].toInt()
            for (k in start until len) {
                child[toBase + 1 + toLen] = s[fromBase + 1 + k]
                toLen++
            }
            child[toBase] = toLen.toByte()
            child[fromBase] = start.toByte()
            return child
        }

        private fun columnToFoundation(s: ByteArray, c: Int, card: Int): ByteArray {
            val child = s.copyOf()
            child[colBase(c)] = (child[colBase(c)] - 1).toByte()
            child[codeSuit(card)]++
            return child
        }

        private fun cellToFoundation(s: ByteArray, cell: Int, card: Int): ByteArray {
            val child = s.copyOf()
            child[CELLS + cell] = -1
            child[codeSuit(card)]++
            return child
        }

        private fun cellToColumn(s: ByteArray, cell: Int, card: Int, to: Int): ByteArray {
            val child = s.copyOf()
            val base = colBase(to)
            val len = s[base].toInt()
            child[base + 1 + len] = card.toByte()
            child[base] = (len + 1).toByte()
            child[CELLS + cell] = -1
            return child
        }

        private fun pack(type: Int, a: Int, b: Int, c: Int): Int =
            type or (a shl A_SHIFT) or (b shl B_SHIFT) or (c shl C_SHIFT)

        private const val T2F = 0
        private const val C2F = 1
        private const val T2T = 2
        private const val C2T = 3
        private const val T2C = 4
        private const val TYPE_MASK = 7
        private const val A_SHIFT = 3
        private const val B_SHIFT = 8
        private const val C_SHIFT = 13
        private const val FIELD_MASK = 31

        private const val SUITS = 4
        private const val FULL_DECK = 52
        private const val CELLS = 4
        private const val COLUMNS = 8
        private const val COLUMN_SPAN = 22
        private const val SIZE = COLUMNS + FREECELL_TABLEAU_COUNT * COLUMN_SPAN

        private const val COLUMN_SEED = 0x4567_89ABL
        private const val CELL_SEED = 0x5678_9ABCL
        private const val CELL_MULT = 0x9E37_79B9L
    }
}
