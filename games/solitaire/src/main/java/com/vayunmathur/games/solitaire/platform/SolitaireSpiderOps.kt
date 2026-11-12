package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.FULL_SUIT_SIZE
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SPIDER_TABLEAU_COUNT
import com.vayunmathur.games.solitaire.data.TOTAL_SPIDER_SUITS
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.data.isOneHigherThan
import kotlinx.coroutines.flow.update

// ---- Spider ----
// Moved from SolitaireViewModel.kt (FileLength split); behavior identical.

fun SolitaireViewModel.newSpiderGame(config: GameConfig) = newSpiderGameImpl(config)

fun SolitaireViewModel.spiderMoveCards(fromColumn: Int, cardIndex: Int, toColumn: Int) =
    spiderMoveCardsImpl(fromColumn, cardIndex, toColumn)

/** A new game on a solver-proven deal for [config] (see SolitaireDealSource). */
internal fun SolitaireViewModel.newSpiderGameImpl(config: GameConfig) = startNewDeal(GameMode.SPIDER, config)

internal fun SolitaireViewModel.dealSpiderStockImpl() {
    val state = uiStateInternal.value.spider ?: return
    if (state.isWon || state.stockGroups.isEmpty()) return
    if (state.tableauPiles.any { it.faceUp.isEmpty() && it.faceDown.isEmpty() }) return
    saveHistory()
    val group = state.stockGroups.first()
    val newPiles = state.tableauPiles.toMutableList()
    for (i in 0 until minOf(group.size, SPIDER_TABLEAU_COUNT)) {
        val pile = newPiles[i]
        newPiles[i] = pile.copy(faceUp = pile.faceUp + group[i])
    }
    uiStateInternal.update {
        it.copy(spider = state.copy(
            tableauPiles = newPiles,
            stockGroups = state.stockGroups.drop(1),
            moveCount = state.moveCount + 1
        ))
    }
    checkSpiderCompletedSuits()
}

internal fun SolitaireViewModel.spiderMoveCardsImpl(fromColumn: Int, cardIndex: Int, toColumn: Int) {
    val state = uiStateInternal.value.spider ?: return
    if (state.isWon) return
    val fromPile = state.tableauPiles[fromColumn]
    if (cardIndex < 0 || cardIndex >= fromPile.faceUp.size) return
    val movingCards = fromPile.faceUp.subList(cardIndex, fromPile.faceUp.size)
    if (!isSpiderSequence(movingCards)) return
    val toPile = state.tableauPiles[toColumn]
    if (!canPlaceOnSpiderTableau(movingCards.first(), toPile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = autoFlip(fromPile.copy(faceUp = fromPile.faceUp.subList(0, cardIndex)))
    newPiles[toColumn] = toPile.copy(faceUp = toPile.faceUp + movingCards)
    uiStateInternal.update {
        it.copy(spider = state.copy(
            tableauPiles = newPiles,
            moveCount = state.moveCount + 1
        ))
    }
    checkSpiderCompletedSuits()
}

internal fun SolitaireViewModel.isSpiderSequence(cards: List<Card>): Boolean =
    cards.zipWithNext().all { (a, b) -> a.suit == b.suit && a.isOneHigherThan(b) }

internal fun SolitaireViewModel.canPlaceOnSpiderTableau(card: Card, pile: TableauPile): Boolean = when {
    pile.faceUp.isEmpty() && pile.faceDown.isEmpty() -> true
    pile.faceUp.isEmpty() -> false
    else -> pile.faceUp.last().rank.value == card.rank.value + 1
}

internal fun SolitaireViewModel.checkSpiderCompletedSuits() {
    val state = uiStateInternal.value.spider ?: return
    val newPiles = state.tableauPiles.toMutableList()
    var completed = state.completedSuits
    for (i in newPiles.indices) {
        val pile = newPiles[i]
        if (pile.faceUp.size >= FULL_SUIT_SIZE) {
            val lastSuit = pile.faceUp.takeLast(FULL_SUIT_SIZE)
            if (isSpiderSequence(lastSuit) && lastSuit.first().rank == Rank.KING &&
                lastSuit.last().rank == Rank.ACE
            ) {
                newPiles[i] = autoFlip(pile.copy(faceUp = pile.faceUp.dropLast(FULL_SUIT_SIZE)))
                completed++
            }
        }
    }
    if (completed != state.completedSuits) {
        val newState = state.copy(
            tableauPiles = newPiles,
            completedSuits = completed,
            isWon = completed >= TOTAL_SPIDER_SUITS,
        )
        uiStateInternal.update { it.copy(spider = newState) }
        if (newState.isWon) {
            onGameWon(GameMode.SPIDER, state.elapsedSeconds, state.usedUndo)
        }
    }
}

internal fun SolitaireViewModel.handleSpiderDropImpl(sourceId: String, targetId: String) {
    if (!targetId.startsWith("tableau_") || !sourceId.startsWith("tableau_")) return
    val toCol = targetId.removePrefix("tableau_").toInt()
    val parts = sourceId.removePrefix("tableau_").split("_")
    val fromCol = parts[0].toInt()
    val cardIdx = parts.getOrNull(1)?.toInt() ?: return
    spiderMoveCards(fromCol, cardIdx, toCol)
}
