package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.FOUNDATION_COUNT
import com.vayunmathur.games.solitaire.data.FREECELL_COUNT
import com.vayunmathur.games.solitaire.data.FREECELL_TABLEAU_COUNT
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.alternatesColorWith
import com.vayunmathur.games.solitaire.data.createShuffledDeck
import com.vayunmathur.games.solitaire.data.isOneHigherThan
import kotlinx.coroutines.flow.update

// ---- FreeCell ----
// Moved from SolitaireViewModel.kt (FileLength split); behavior identical.

fun SolitaireViewModel.newFreeCellGame() = newFreeCellGameImpl()

fun SolitaireViewModel.freeCellMoveToFreeCell(fromColumn: Int, cellIndex: Int) =
    freeCellMoveToFreeCellImpl(fromColumn, cellIndex)

fun SolitaireViewModel.freeCellMoveFromFreeCell(cellIndex: Int, toColumn: Int) =
    freeCellMoveFromFreeCellImpl(cellIndex, toColumn)

fun SolitaireViewModel.freeCellMoveFreeCellToFoundation(cellIndex: Int, foundationIndex: Int) =
    freeCellMoveFreeCellToFoundationImpl(cellIndex, foundationIndex)

fun SolitaireViewModel.freeCellMoveTableauToFoundation(fromColumn: Int, foundationIndex: Int) =
    freeCellMoveTableauToFoundationImpl(fromColumn, foundationIndex)

fun SolitaireViewModel.freeCellMoveTableauToTableau(fromColumn: Int, cardIndex: Int, toColumn: Int) =
    freeCellMoveTableauToTableauImpl(fromColumn, cardIndex, toColumn)

internal fun SolitaireViewModel.newFreeCellGameImpl() {
    val deck = createShuffledDeck()
    val piles = List(FREECELL_TABLEAU_COUNT) { mutableListOf<Card>() }
    for (i in deck.indices) {
        piles[i % FREECELL_TABLEAU_COUNT].add(deck[i])
    }
    uiStateInternal.value = SolitaireUiState(
        gameMode = GameMode.FREECELL,
        freeCell = FreeCellState(
            tableauPiles = piles.map { it.toList() },
            freeCells = List(FREECELL_COUNT) { null },
            foundations = List(FOUNDATION_COUNT) { emptyList() },
            variant = "STANDARD"
        ),
        history = emptyList()
    )
    statsRepository.recordGamePlayed(GameMode.FREECELL, "STANDARD")
}

internal fun SolitaireViewModel.freeCellMoveToFreeCellImpl(fromColumn: Int, cellIndex: Int) {
    val state = uiStateInternal.value.freeCell ?: return
    if (state.isWon) return
    val pile = state.tableauPiles[fromColumn]
    if (pile.isEmpty()) return
    if (state.freeCells[cellIndex] != null) return
    saveHistory()
    val card = pile.last()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = pile.dropLast(1)
    val newCells = state.freeCells.toMutableList()
    newCells[cellIndex] = card
    uiStateInternal.update {
        it.copy(freeCell = state.copy(
            tableauPiles = newPiles,
            freeCells = newCells,
            moveCount = state.moveCount + 1
        ))
    }
}

internal fun SolitaireViewModel.freeCellMoveFromFreeCellImpl(cellIndex: Int, toColumn: Int) {
    val state = uiStateInternal.value.freeCell ?: return
    if (state.isWon) return
    val card = state.freeCells[cellIndex] ?: return
    val pile = state.tableauPiles[toColumn]
    if (!canPlaceOnFreeCellTableau(card, pile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[toColumn] = pile + card
    val newCells = state.freeCells.toMutableList()
    newCells[cellIndex] = null
    uiStateInternal.update {
        it.copy(freeCell = state.copy(
            tableauPiles = newPiles,
            freeCells = newCells,
            moveCount = state.moveCount + 1
        ))
    }
}

internal fun SolitaireViewModel.freeCellMoveFreeCellToFoundationImpl(cellIndex: Int, foundationIndex: Int) {
    val state = uiStateInternal.value.freeCell ?: return
    if (state.isWon) return
    val card = state.freeCells[cellIndex] ?: return
    if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
    saveHistory()
    val newCells = state.freeCells.toMutableList()
    newCells[cellIndex] = null
    val newFoundations = state.foundations.toMutableList()
    newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
    uiStateInternal.update {
        it.copy(freeCell = state.copy(
            freeCells = newCells,
            foundations = newFoundations,
            moveCount = state.moveCount + 1
        ))
    }
    checkFreeCellWin()
}

internal fun SolitaireViewModel.freeCellMoveTableauToFoundationImpl(fromColumn: Int, foundationIndex: Int) {
    val state = uiStateInternal.value.freeCell ?: return
    if (state.isWon) return
    val pile = state.tableauPiles[fromColumn]
    if (pile.isEmpty()) return
    val card = pile.last()
    if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = pile.dropLast(1)
    val newFoundations = state.foundations.toMutableList()
    newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
    uiStateInternal.update {
        it.copy(freeCell = state.copy(
            tableauPiles = newPiles,
            foundations = newFoundations,
            moveCount = state.moveCount + 1
        ))
    }
    checkFreeCellWin()
}

internal fun SolitaireViewModel.freeCellMoveTableauToTableauImpl(fromColumn: Int, cardIndex: Int, toColumn: Int) {
    val state = uiStateInternal.value.freeCell ?: return
    if (state.isWon) return
    val fromPile = state.tableauPiles[fromColumn]
    if (cardIndex < 0 || cardIndex >= fromPile.size) return
    val movingCards = fromPile.subList(cardIndex, fromPile.size)
    if (!isFreeCellSequence(movingCards)) return
    val toPile = state.tableauPiles[toColumn]
    if (movingCards.size > 1) {
        val emptyFreeCells = state.freeCells.count { it == null }
        val emptyColumns = state.tableauPiles.indices.count {
            it != fromColumn && it != toColumn && state.tableauPiles[it].isEmpty()
        }
        val maxMove = (1 + emptyFreeCells) * (1 shl emptyColumns)
        if (movingCards.size > maxMove) return
    }
    if (!canPlaceOnFreeCellTableau(movingCards.first(), toPile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = fromPile.subList(0, cardIndex)
    newPiles[toColumn] = toPile + movingCards
    uiStateInternal.update {
        it.copy(freeCell = state.copy(
            tableauPiles = newPiles,
            moveCount = state.moveCount + 1
        ))
    }
}

internal fun SolitaireViewModel.isFreeCellSequence(cards: List<Card>): Boolean =
    cards.zipWithNext().all { (a, b) -> a.alternatesColorWith(b) && a.isOneHigherThan(b) }

internal fun SolitaireViewModel.canPlaceOnFreeCellTableau(card: Card, pile: List<Card>): Boolean =
    pile.isEmpty() || (card.alternatesColorWith(pile.last()) && pile.last().isOneHigherThan(card))

internal fun SolitaireViewModel.checkFreeCellWin() {
    val state = uiStateInternal.value.freeCell ?: return
    if (state.foundations.all { it.size == Rank.KING.value }) {
        uiStateInternal.update { it.copy(freeCell = state.copy(isWon = true)) }
        onGameWon(GameMode.FREECELL, state.elapsedSeconds, state.usedUndo)
    }
}

internal fun SolitaireViewModel.handleFreeCellDropImpl(sourceId: String, targetId: String) {
    when {
        targetId.startsWith("freecell_") -> {
            val ci = targetId.removePrefix("freecell_").toInt()
            // A free cell holds one card, so only a single-card drag may
            // land here. Without this guard a multi-card drag dropped on
            // a cell silently moved the pile's top card instead of the
            // stack the player actually grabbed.
            if (isSingleCardDrag(sourceId) && sourceId.startsWith("tableau_")) {
                val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                freeCellMoveToFreeCell(col, ci)
            }
        }
        targetId.startsWith("foundation_") -> {
            val fi = targetId.removePrefix("foundation_").toInt()
            if (isSingleCardDrag(sourceId)) {
                when {
                    sourceId.startsWith("freecell_") -> {
                        val ci = sourceId.removePrefix("freecell_").toInt()
                        freeCellMoveFreeCellToFoundation(ci, fi)
                    }
                    sourceId.startsWith("tableau_") -> {
                        val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                        freeCellMoveTableauToFoundation(col, fi)
                    }
                }
            }
        }
        targetId.startsWith("tableau_") -> {
            val toCol = targetId.removePrefix("tableau_").toInt()
            when {
                sourceId.startsWith("freecell_") -> {
                    val ci = sourceId.removePrefix("freecell_").toInt()
                    freeCellMoveFromFreeCell(ci, toCol)
                }
                sourceId.startsWith("tableau_") -> {
                    val parts = sourceId.removePrefix("tableau_").split("_")
                    val fromCol = parts[0].toInt()
                    val cardIdx = parts.getOrNull(1)?.toInt() ?: return
                    freeCellMoveTableauToTableau(fromCol, cardIdx, toCol)
                }
            }
        }
    }
}

internal fun SolitaireViewModel.freeCellAutoMoveImpl(sourceId: String) {
    planFreeCellAutoMove(sourceId)?.apply?.invoke()
}

/**
 * The auto-move a tap on [sourceId] would make, decided without applying
 * it: the destination pile id plus the move itself. Tap-to-move animation
 * needs the destination (and its on-screen rectangle) before anything
 * moves; the instant path simply applies the plan.
 */
internal fun SolitaireViewModel.planFreeCellAutoMove(sourceId: String): PlannedMove? {
    val state = uiStateInternal.value.freeCell ?: return null
    if (state.isWon) return null
    val cards = draggedCards(sourceId)
    if (cards.isEmpty()) return null
    val origin = freeCellMoveOrigin(sourceId) ?: return null
    return planFoundationMove(state, cards, origin)
        ?: planTableauMove(state, cards, origin)
        ?: planEmptyCellMove(state, cards, origin)
}

private data class FreeCellMoveOrigin(val fromCol: Int?, val fromIdx: Int, val fromCell: Int?)

private fun freeCellMoveOrigin(sourceId: String): FreeCellMoveOrigin? {
    if (sourceId.startsWith("tableau_")) {
        val parts = sourceId.removePrefix("tableau_").split("_")
        return FreeCellMoveOrigin(
            fromCol = parts.getOrNull(0)?.toIntOrNull() ?: return null,
            fromIdx = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            fromCell = null,
        )
    }
    if (sourceId.startsWith("freecell_")) {
        return FreeCellMoveOrigin(
            fromCol = null,
            fromIdx = 0,
            fromCell = sourceId.removePrefix("freecell_").toIntOrNull() ?: return null,
        )
    }
    return null
}

private fun SolitaireViewModel.planFoundationMove(
    state: FreeCellState,
    cards: List<Card>,
    origin: FreeCellMoveOrigin,
): PlannedMove? {
    if (cards.size != SINGLE_CARD) return null
    val topCard = cards.first()
    for (fi in state.foundations.indices) {
        if (!canPlaceOnFoundation(topCard, state.foundations[fi], fi)) continue
        return when {
            origin.fromCell != null -> PlannedMove("foundation_$fi") {
                freeCellMoveFreeCellToFoundation(origin.fromCell, fi)
            }
            origin.fromCol != null -> PlannedMove("foundation_$fi") {
                freeCellMoveTableauToFoundation(origin.fromCol, fi)
            }
            else -> return null
        }
    }
    return null
}

private fun SolitaireViewModel.planTableauMove(
    state: FreeCellState,
    cards: List<Card>,
    origin: FreeCellMoveOrigin,
): PlannedMove? {
    val target = state.tableauPiles.indices.firstOrNull { toCol ->
        toCol != origin.fromCol && isViableTableauTarget(state, cards, origin, toCol)
    } ?: return null
    return when {
        origin.fromCell != null -> PlannedMove("tableau_$target") {
            freeCellMoveFromFreeCell(origin.fromCell, target)
        }
        origin.fromCol != null -> PlannedMove("tableau_$target") {
            freeCellMoveTableauToTableau(origin.fromCol, origin.fromIdx, target)
        }
        else -> null
    }
}

private fun isLoneCardChurn(
    state: FreeCellState,
    cards: List<Card>,
    origin: FreeCellMoveOrigin,
    toPile: List<Card>,
): Boolean {
    if (toPile.isNotEmpty() || origin.fromCol == null) return false
    if (cards.size != SINGLE_CARD) return false
    return state.tableauPiles[origin.fromCol].size == SINGLE_CARD
}

private fun SolitaireViewModel.isViableTableauTarget(
    state: FreeCellState,
    cards: List<Card>,
    origin: FreeCellMoveOrigin,
    toCol: Int,
): Boolean {
    val toPile = state.tableauPiles[toCol]
    if (!canPlaceOnFreeCellTableau(cards.first(), toPile)) return false
    // Skip moving a lone card between empty columns — pointless churn.
    return !isLoneCardChurn(state, cards, origin, toPile)
}

private fun SolitaireViewModel.planEmptyCellMove(
    state: FreeCellState,
    cards: List<Card>,
    origin: FreeCellMoveOrigin,
): PlannedMove? {
    // Empty free cell — last resort for a single card off a tableau column.
    if (cards.size != SINGLE_CARD || origin.fromCol == null) return null
    val emptyCell = state.freeCells.indexOfFirst { it == null }
    if (emptyCell < 0) return null
    return PlannedMove("freecell_$emptyCell") { freeCellMoveToFreeCell(origin.fromCol, emptyCell) }
}

internal const val SINGLE_CARD = 1
