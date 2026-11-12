package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.KLONDIKE_TABLEAU_COUNT
import com.vayunmathur.games.solitaire.data.KlondikeState

/** One player action in a Klondike winning line, in the ViewModel's own vocabulary. */
sealed interface KlondikeMove {
    /** Tap the stock: draw (1 or 3) or, when it is empty, recycle the waste. */
    data object Draw : KlondikeMove

    /** Waste top to foundation [foundation] (foundations are suit-indexed). */
    data class WasteToFoundation(val foundation: Int) : KlondikeMove

    /** Waste top onto column [column]. */
    data class WasteToTableau(val column: Int) : KlondikeMove

    /** Top of column [column] to foundation [foundation]. */
    data class TableauToFoundation(val column: Int, val foundation: Int) : KlondikeMove

    /** Moves the face-up run starting at [faceUpIndex] of column [from] onto column [to]. */
    data class TableauToTableau(val from: Int, val faceUpIndex: Int, val to: Int) : KlondikeMove
}

/**
 * Klondike solver for every draw mode and pass limit.
 *
 * The stock is handled as macro moves: from any position it enumerates every
 * card that can reach the waste top by tapping the stock (drawing [drawCount] at
 * a time and recycling while [KlondikeState.redealsRemaining] allows), and plays
 * it in one edge with the taps folded in. Taps that play nothing are never
 * needed, so this is complete for the stock while removing its long, cyclic
 * chains from the search. Columns are interchangeable, so the transposition key
 * ignores their order. A tableau card is auto-played to its foundation when no
 * opposite-colour card could still need it; that move never costs a win.
 */
class KlondikeSolver private constructor(private val drawCount: Int) : SearchProblem {

    override fun isWon(state: ByteArray): Boolean =
        (0 until SUITS).all { state[it].toInt() == RANKS }

    override fun key(state: ByteArray): Long {
        var columns = 0L
        for (c in 0 until KLONDIKE_TABLEAU_COUNT) {
            val base = colBase(c)
            columns += mix64(hashBytes(state, base, state[base] + 2, COLUMN_SEED))
        }
        val stock = hashBytes(state, STOCK, state[STOCK_N].toInt(), STOCK_SEED)
        val waste = hashBytes(state, WASTE, state[WASTE_N].toInt(), WASTE_SEED)
        return mix64(columns xor mix64(stock + state[REDEALS]) xor (waste * WASTE_MULT))
    }

    override fun score(state: ByteArray): Int {
        var down = 0
        var buried = 0
        for (c in 0 until KLONDIKE_TABLEAU_COUNT) {
            val base = colBase(c)
            val d = state[base + 1].toInt()
            down += d
            // Face-down cards under a deep pile are slower to reach.
            if (d > 0) buried += d * (state[base].toInt() - d)
        }
        return 4 * down + 2 * (FULL_DECK - foundationTotal(state)) + buried / 2 +
            state[STOCK_N] + state[WASTE_N]
    }

    override fun expand(state: ByteArray, sink: ChildSink) {
        if (emitSafeAutoPlay(state, sink)) return
        emitTableauToTableau(state, sink, revealing = true)
        emitTableauToFoundation(state, sink)
        emitTalonPlays(state, sink)
        emitTableauToTableau(state, sink, revealing = false)
    }

    /** A tableau top that is safe to send up is the only child: no branching needed. */
    private fun emitSafeAutoPlay(state: ByteArray, sink: ChildSink): Boolean {
        for (c in 0 until KLONDIKE_TABLEAU_COUNT) {
            val top = topCard(state, c)
            if (top >= 0 && isSafeToFoundation(state, top)) {
                val child = state.copyOf()
                popFromColumn(child, c, 1)
                child[codeSuit(top)]++
                sink.child(child, pack(T2F, c, codeSuit(top), 0, 0))
                return true
            }
        }
        return false
    }

    private fun emitTableauToFoundation(state: ByteArray, sink: ChildSink) {
        for (c in 0 until KLONDIKE_TABLEAU_COUNT) {
            val top = topCard(state, c)
            if (top < 0 || !fitsFoundation(state, top)) continue
            val child = state.copyOf()
            popFromColumn(child, c, 1)
            child[codeSuit(top)]++
            sink.child(child, pack(T2F, c, codeSuit(top), 0, 0))
        }
    }

    /** Every card reachable on the waste top, played to a foundation or a column. */
    private fun emitTalonPlays(state: ByteArray, sink: ChildSink) {
        val sim = state.copyOf()
        var taps = 0
        var recycles = 0
        var seenCards = 0L
        while (true) {
            val top = if (sim[WASTE_N] > 0) sim[WASTE + sim[WASTE_N] - 1].toInt() else -1
            if (top >= 0 && (seenCards and (1L shl top)) == 0L) {
                seenCards = seenCards or (1L shl top)
                emitWastePlays(sim, top, taps, sink)
            }
            // A third pass would only repeat the second, which started from a full stock.
            if (sim[STOCK_N].toInt() == 0 && recycles == 1) return
            val recycled = sim[STOCK_N].toInt() == 0
            if (!tapStock(sim)) return
            if (recycled) recycles++
            taps++
        }
    }

    private fun emitWastePlays(atTop: ByteArray, card: Int, taps: Int, sink: ChildSink) {
        if (fitsFoundation(atTop, card)) {
            val child = atTop.copyOf()
            child[WASTE_N]--
            child[codeSuit(card)]++
            sink.child(child, pack(W2F, 0, codeSuit(card), 0, taps))
        }
        var emptyTried = false
        for (c in 0 until KLONDIKE_TABLEAU_COUNT) {
            if (!fitsColumn(atTop, card, c)) continue
            val empty = atTop[colBase(c)].toInt() == 0
            if (empty && emptyTried) continue
            emptyTried = emptyTried || empty
            val child = atTop.copyOf()
            child[WASTE_N]--
            pushToColumn(child, c, card)
            sink.child(child, pack(W2T, c, 0, 0, taps))
        }
    }

    /**
     * Run moves. [revealing] picks the whole face-up run of a column with cards
     * still face-down (always progress); otherwise it is a run that empties its
     * column, or a part-run whose removal frees a card for its foundation.
     */
    private fun emitTableauToTableau(state: ByteArray, sink: ChildSink, revealing: Boolean) {
        for (from in 0 until KLONDIKE_TABLEAU_COUNT) {
            val base = colBase(from)
            val len = state[base].toInt()
            val down = state[base + 1].toInt()
            if (len == 0) continue
            for (i in down until len) {
                if (!isUsefulRunStart(state, from, i, revealing)) continue
                emitRunTargets(state, from, i, sink)
            }
        }
    }

    private fun isUsefulRunStart(state: ByteArray, from: Int, i: Int, revealing: Boolean): Boolean {
        val base = colBase(from)
        val down = state[base + 1].toInt()
        return when {
            i == down && down > 0 -> revealing
            revealing -> false
            i == down -> true
            else -> fitsFoundation(state, state[base + 2 + i - 1].toInt())
        }
    }

    private fun emitRunTargets(state: ByteArray, from: Int, i: Int, sink: ChildSink) {
        val base = colBase(from)
        val len = state[base].toInt()
        val down = state[base + 1].toInt()
        val card = state[base + 2 + i].toInt()
        var emptyTried = false
        for (to in 0 until KLONDIKE_TABLEAU_COUNT) {
            if (to == from || !fitsColumn(state, card, to)) continue
            val empty = state[colBase(to)].toInt() == 0
            // A King run already at the bottom gains nothing by changing columns.
            val pointlessEmpty = emptyTried || (i == 0 && down == 0)
            if (empty && pointlessEmpty) continue
            emptyTried = emptyTried || empty
            val child = state.copyOf()
            for (k in i until len) pushToColumn(child, to, state[base + 2 + k].toInt())
            popFromColumn(child, from, len - i)
            sink.child(child, pack(T2T, from, i - down, to, 0))
        }
    }

    private fun isSafeToFoundation(state: ByteArray, card: Int): Boolean {
        if (!fitsFoundation(state, card)) return false
        val rank = codeRank(card)
        if (rank <= 2) return true
        val firstOpposite = if (codeIsRed(card)) 2 else 0
        return state[firstOpposite] >= rank - 1 && state[firstOpposite + 1] >= rank - 1
    }

    /** One stock tap with the ViewModel's exact semantics; false when it does nothing. */
    private fun tapStock(s: ByteArray): Boolean {
        val stockN = s[STOCK_N].toInt()
        val wasteN = s[WASTE_N].toInt()
        if (stockN > 0) {
            val n = minOf(drawCount, stockN)
            for (k in 0 until n) s[WASTE + wasteN + k] = s[STOCK + stockN - 1 - k]
            s[STOCK_N] = (stockN - n).toByte()
            s[WASTE_N] = (wasteN + n).toByte()
            return true
        }
        val redeals = s[REDEALS].toInt() and 0xFF
        if (wasteN == 0 || redeals == 0) return false
        for (k in 0 until wasteN) s[STOCK + k] = s[WASTE + wasteN - 1 - k]
        s[STOCK_N] = wasteN.toByte()
        s[WASTE_N] = 0
        if (redeals != UNLIMITED) s[REDEALS] = (redeals - 1).toByte()
        return true
    }

    companion object {
        /**
         * Solves [state] (any position, normally a fresh deal) within [budget].
         * The pass limit comes from the state's own
         * [KlondikeState.redealsRemaining].
         */
        fun solve(state: KlondikeState, budget: SolverBudget = DEFAULT_BUDGET): SolveResult =
            portfolioSearch(KlondikeSolver(state.drawMode.cardsPerDraw()), encode(state), budget)

        /** The budget the shipped tables were generated with. */
        val DEFAULT_BUDGET = SolverBudget(dfsNodes = 300_000)

        /** Expands packed codes into the taps and moves a player makes. */
        fun decode(moves: IntArray): List<KlondikeMove> = buildList {
            for (m in moves) {
                repeat(field(m, DRAWS_SHIFT, DRAWS_BITS)) { add(KlondikeMove.Draw) }
                val a = field(m, A_SHIFT, FIELD_BITS)
                val b = field(m, B_SHIFT, FIELD_BITS)
                when (m and TYPE_MASK) {
                    T2F -> add(KlondikeMove.TableauToFoundation(a, b))
                    W2F -> add(KlondikeMove.WasteToFoundation(b))
                    W2T -> add(KlondikeMove.WasteToTableau(a))
                    else -> add(KlondikeMove.TableauToTableau(a, b, field(m, C_SHIFT, FIELD_BITS)))
                }
            }
        }

        private fun encode(state: KlondikeState): ByteArray {
            val s = ByteArray(SIZE)
            for (f in 0 until SUITS) s[f] = state.foundations[f].size.toByte()
            s[REDEALS] = (if (state.redealsRemaining >= UNLIMITED) UNLIMITED else state.redealsRemaining).toByte()
            s[STOCK_N] = state.stock.size.toByte()
            state.stock.forEachIndexed { i, card -> s[STOCK + i] = card.code().toByte() }
            s[WASTE_N] = state.waste.size.toByte()
            state.waste.forEachIndexed { i, card -> s[WASTE + i] = card.code().toByte() }
            state.tableauPiles.forEachIndexed { c, pile ->
                val base = colBase(c)
                val cards = pile.faceDown + pile.faceUp
                s[base] = cards.size.toByte()
                s[base + 1] = pile.faceDown.size.toByte()
                cards.forEachIndexed { i, card -> s[base + 2 + i] = card.code().toByte() }
            }
            return s
        }

        private fun colBase(c: Int) = COLUMNS + c * COLUMN_SPAN

        private fun foundationTotal(s: ByteArray) = s[0] + s[1] + s[2] + s[3]

        private fun topCard(s: ByteArray, c: Int): Int {
            val base = colBase(c)
            val len = s[base].toInt()
            return if (len == 0) -1 else s[base + 1 + len].toInt()
        }

        private fun fitsFoundation(s: ByteArray, card: Int) = s[codeSuit(card)].toInt() == codeRank(card) - 1

        private fun fitsColumn(s: ByteArray, card: Int, c: Int): Boolean {
            val top = topCard(s, c)
            if (top < 0) return codeRank(card) == RANKS
            return codeIsRed(top) != codeIsRed(card) && codeRank(top) == codeRank(card) + 1
        }

        private fun pushToColumn(s: ByteArray, c: Int, card: Int) {
            val base = colBase(c)
            val len = s[base].toInt()
            s[base + 2 + len] = card.toByte()
            s[base] = (len + 1).toByte()
        }

        /** Removes [count] top cards, then flips the new top face-up like the game does. */
        private fun popFromColumn(s: ByteArray, c: Int, count: Int) {
            val base = colBase(c)
            val len = s[base] - count
            s[base] = len.toByte()
            if (len > 0 && s[base + 1].toInt() == len) s[base + 1] = (len - 1).toByte()
        }

        private fun pack(type: Int, a: Int, b: Int, c: Int, draws: Int): Int =
            type or (a shl A_SHIFT) or (b shl B_SHIFT) or (c shl C_SHIFT) or (draws shl DRAWS_SHIFT)

        private fun field(m: Int, shift: Int, bits: Int) = (m ushr shift) and ((1 shl bits) - 1)

        private const val T2F = 0
        private const val W2F = 1
        private const val W2T = 2
        private const val T2T = 3
        private const val TYPE_MASK = 3
        private const val A_SHIFT = 2
        private const val B_SHIFT = 7
        private const val C_SHIFT = 12
        private const val DRAWS_SHIFT = 17
        private const val FIELD_BITS = 5
        private const val DRAWS_BITS = 8

        private const val SUITS = 4
        private const val FULL_DECK = 52
        private const val UNLIMITED = 255
        private const val REDEALS = 4
        private const val STOCK_N = 5
        private const val WASTE_N = 6
        private const val STOCK = 7
        private const val WASTE = 31
        private const val COLUMNS = 55
        private const val COLUMN_SPAN = 21
        private const val SIZE = COLUMNS + KLONDIKE_TABLEAU_COUNT * COLUMN_SPAN

        private const val COLUMN_SEED = 0x1234_5678L
        private const val STOCK_SEED = 0x2345_6789L
        private const val WASTE_SEED = 0x3456_789AL
        private const val WASTE_MULT = 0x9E37_79B9L
    }
}
