package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.data.isOneHigherThan

// ---- Klondike auto-complete planning (pure) ----
// The run, the skip and the offer all derive from these functions, so the button is only
// offered for a board the run will actually finish, and the run can never strand itself.

/** One move of a Klondike auto-complete, with the board it leaves behind. */
sealed interface AutoCompleteStep {
    val after: KlondikeState

    /** [card] goes from [sourceId] (`waste` or `tableau_<col>_<index>`) up to foundation [foundationIndex]. */
    data class ToFoundation(
        val card: Card,
        val sourceId: String,
        val foundationIndex: Int,
        override val after: KlondikeState,
    ) : AutoCompleteStep

    /** Draws from the stock onto the waste: one card or three, as the deal's draw mode says. */
    data class Draw(override val after: KlondikeState) : AutoCompleteStep

    /** Turns the waste back over into the stock, spending one of the deal's passes. */
    data class Recycle(override val after: KlondikeState) : AutoCompleteStep
}

/**
 * The move auto-complete makes next from [state], playing as a player would with only
 * foundation moves: the waste top to its foundation, else the first tableau top that fits,
 * else draw (respecting draw-one/draw-three), else recycle the waste if a pass is left.
 * Null when none of those is possible. Moving a card up never removes an option, so taking
 * any fitting card before drawing is safe.
 */
fun nextKlondikeAutoCompleteStep(state: KlondikeState): AutoCompleteStep? =
    wasteToFoundation(state)
        ?: tableauToFoundation(state)
        ?: drawStep(state)
        ?: recycleStep(state)

/**
 * Every step auto-complete takes from [state] to a finished board, or null when it cannot
 * finish under the deal's draw mode and pass limit (or must not be offered: a face-down
 * card is still on the tableau, or the game is already won).
 *
 * A pass that turns over the whole stock without a single foundation move leaves the board
 * exactly as it was, so a second recycle with no progress since the first means the run
 * would cycle forever with unlimited passes; that board cannot finish either.
 */
fun planKlondikeAutoComplete(state: KlondikeState): List<AutoCompleteStep>? {
    if (state.isWon || state.tableauPiles.any { it.faceDown.isNotEmpty() }) return null
    val steps = mutableListOf<AutoCompleteStep>()
    var current = state
    var recycled = false
    var progressSinceRecycle = false
    while (!current.allOnFoundations()) {
        val step = nextKlondikeAutoCompleteStep(current) ?: return null
        when (step) {
            is AutoCompleteStep.ToFoundation -> progressSinceRecycle = true
            is AutoCompleteStep.Recycle -> {
                if (recycled && !progressSinceRecycle) return null
                recycled = true
                progressSinceRecycle = false
            }
            is AutoCompleteStep.Draw -> Unit
        }
        steps += step
        current = step.after
        if (steps.size > MAX_PLAN_STEPS) return null
    }
    return steps
}

/** Whether auto-complete may be offered: only when [planKlondikeAutoComplete] finishes. */
fun canKlondikeAutoComplete(state: KlondikeState): Boolean = planKlondikeAutoComplete(state) != null

/** The board a finished auto-complete leaves, or [state] itself when it cannot finish. */
internal fun finishedKlondikeAutoComplete(state: KlondikeState): KlondikeState =
    planKlondikeAutoComplete(state)?.lastOrNull()?.after ?: state

private fun KlondikeState.allOnFoundations(): Boolean =
    foundations.sumOf { it.size } == Suit.entries.size * Rank.entries.size

private fun wasteToFoundation(state: KlondikeState): AutoCompleteStep? {
    val card = state.waste.lastOrNull() ?: return null
    val fi = fittingFoundation(state, card) ?: return null
    return AutoCompleteStep.ToFoundation(
        card = card,
        sourceId = WASTE,
        foundationIndex = fi,
        after = state.copy(
            waste = state.waste.dropLast(1),
            foundations = state.foundations.withCardOn(fi, card),
            moveCount = state.moveCount + 1,
        ),
    )
}

private fun tableauToFoundation(state: KlondikeState): AutoCompleteStep? {
    val (col, fi) = state.tableauPiles.withIndex().firstNotNullOfOrNull { (c, pile) ->
        pile.faceUp.lastOrNull()?.let { fittingFoundation(state, it) }?.let { c to it }
    } ?: return null
    val pile = state.tableauPiles[col]
    val card = pile.faceUp.last()
    val piles = state.tableauPiles.toMutableList()
    piles[col] = flipped(pile.copy(faceUp = pile.faceUp.dropLast(1)))
    return AutoCompleteStep.ToFoundation(
        card = card,
        sourceId = "$TABLEAU_PREFIX${col}_${pile.faceUp.lastIndex}",
        foundationIndex = fi,
        after = state.copy(
            tableauPiles = piles,
            foundations = state.foundations.withCardOn(fi, card),
            moveCount = state.moveCount + 1,
        ),
    )
}

/** Draws like the stock tap does (the drawn cards' last ends up on top); not counted as a move. */
private fun drawStep(state: KlondikeState): AutoCompleteStep? {
    if (state.stock.isEmpty()) return null
    val count = if (state.drawMode == DrawMode.DRAW_THREE) DRAW_THREE_COUNT else 1
    val drawn = state.stock.takeLast(count).reversed()
    return AutoCompleteStep.Draw(state.copy(stock = state.stock.dropLast(count), waste = state.waste + drawn))
}

/** Recycles like the empty-stock tap does; unlimited passes stay unlimited. */
private fun recycleStep(state: KlondikeState): AutoCompleteStep? {
    if (state.waste.isEmpty() || state.redealsRemaining <= 0) return null
    val remaining = if (state.redealsRemaining == Int.MAX_VALUE) Int.MAX_VALUE else state.redealsRemaining - 1
    return AutoCompleteStep.Recycle(
        state.copy(stock = state.waste.reversed(), waste = emptyList(), redealsRemaining = remaining),
    )
}

private fun fittingFoundation(state: KlondikeState, card: Card): Int? =
    state.foundations.indices.firstOrNull { fi ->
        val pile = state.foundations[fi]
        if (pile.isEmpty()) card.rank == Rank.ACE && card.suit == Suit.entries[fi]
        else card.suit == pile.last().suit && card.isOneHigherThan(pile.last())
    }

private fun List<List<Card>>.withCardOn(index: Int, card: Card): List<List<Card>> =
    mapIndexed { i, pile -> if (i == index) pile + card else pile }

private fun flipped(pile: TableauPile): TableauPile =
    if (pile.faceUp.isEmpty() && pile.faceDown.isNotEmpty()) {
        pile.copy(faceDown = pile.faceDown.dropLast(1), faceUp = listOf(pile.faceDown.last()))
    } else {
        pile
    }

private const val DRAW_THREE_COUNT = 3

/** Far above any real plan (52 foundation moves plus at most a stock's worth of draws per productive pass). */
private const val MAX_PLAN_STEPS = 5_000
