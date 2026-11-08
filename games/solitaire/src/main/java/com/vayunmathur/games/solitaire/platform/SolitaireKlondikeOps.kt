package com.vayunmathur.games.solitaire.platform

import androidx.compose.ui.geometry.Rect
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.FOUNDATION_COUNT
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KLONDIKE_TABLEAU_COUNT
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.data.alternatesColorWith
import com.vayunmathur.games.solitaire.data.createShuffledDeck
import com.vayunmathur.games.solitaire.data.isOneHigherThan
import com.vayunmathur.games.solitaire.data.redeals
import kotlinx.coroutines.flow.update

// ---- Klondike ----
// Moved from SolitaireViewModel.kt (FileLength split); behavior identical.

fun SolitaireViewModel.newKlondikeGame(config: GameConfig) = newKlondikeGameImpl(config)

fun SolitaireViewModel.klondikeMoveWasteToTableau(columnIndex: Int) =
    klondikeMoveWasteToTableauImpl(columnIndex)

fun SolitaireViewModel.klondikeMoveWasteToFoundation(foundationIndex: Int) =
    klondikeMoveWasteToFoundationImpl(foundationIndex)

fun SolitaireViewModel.klondikeMoveTableauToFoundation(fromColumn: Int, foundationIndex: Int) =
    klondikeMoveTableauToFoundationImpl(fromColumn, foundationIndex)

fun SolitaireViewModel.klondikeMoveTableauToTableau(fromColumn: Int, cardIndex: Int, toColumn: Int) =
    klondikeMoveTableauToTableauImpl(fromColumn, cardIndex, toColumn)

fun SolitaireViewModel.tryMoveByDrag(
    sourceId: String,
    dropOffset: androidx.compose.ui.geometry.Offset,
    cardSize: androidx.compose.ui.unit.IntSize = androidx.compose.ui.unit.IntSize.Zero,
) = tryMoveByDragImpl(sourceId, dropOffset, cardSize)

internal fun SolitaireViewModel.newKlondikeGameImpl(config: GameConfig) {
    val deck = createShuffledDeck()
    val tableauPiles = mutableListOf<TableauPile>()
    var index = 0
    for (i in 0 until KLONDIKE_TABLEAU_COUNT) {
        val faceDown = deck.subList(index, index + i)
        index += i
        val faceUp = listOf(deck[index])
        index++
        tableauPiles.add(TableauPile(faceDown, faceUp))
    }
    val stock = deck.subList(index, deck.size)
    val variant = "${config.drawMode.name}_${config.klondikeDifficulty.name}"
    uiStateInternal.value = SolitaireUiState(
        gameMode = GameMode.KLONDIKE,
        klondike = KlondikeState(
            stock = stock,
            waste = emptyList(),
            tableauPiles = tableauPiles,
            foundations = List(FOUNDATION_COUNT) { emptyList() },
            drawMode = config.drawMode,
            difficulty = config.klondikeDifficulty,
            redealsRemaining = config.klondikeDifficulty.redeals(),
            variant = variant
        ),
        history = emptyList()
    )
    statsRepository.recordGamePlayed(GameMode.KLONDIKE, variant)
}

internal fun SolitaireViewModel.drawFromStockImpl() {
    // A waste-card flight is in the air; drawing would swap the card it
    // is carrying before it lands. Flights are short: ignore the tap.
    if (isMoveAnimating()) return
    val state = uiStateInternal.value.klondike ?: return
    if (state.isWon) return
    if (state.stock.isEmpty() && state.waste.isEmpty()) return
    if (state.stock.isEmpty()) {
        // Recycle the waste into the stock. Redeals are limited by difficulty
        // (Hard = 0, Regular = 2, Relaxed = unlimited).
        if (state.redealsRemaining <= 0) return
        saveHistory()
        uiStateInternal.update {
            it.copy(klondike = state.copy(
                stock = state.waste.reversed(),
                waste = emptyList(),
                redealsRemaining = remainingAfterRecycle(state.redealsRemaining),
                moveCount = state.moveCount + 1
            ))
        }
    } else {
        saveHistory()
    val drawCount = if (state.drawMode == DrawMode.DRAW_THREE) 3 else 1
        val drawn = state.stock.takeLast(drawCount).reversed()
        uiStateInternal.update {
            it.copy(klondike = state.copy(
                stock = state.stock.dropLast(drawCount),
                waste = state.waste + drawn,
                moveCount = state.moveCount + 1
            ))
        }
    }
}

internal fun SolitaireViewModel.klondikeMoveWasteToTableauImpl(columnIndex: Int) {
    val state = uiStateInternal.value.klondike ?: return
    if (state.isWon || state.waste.isEmpty()) return
    val card = state.waste.last()
    val pile = state.tableauPiles[columnIndex]
    if (!canPlaceOnKlondikeTableau(card, pile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[columnIndex] = pile.copy(faceUp = pile.faceUp + card)
    uiStateInternal.update {
        it.copy(klondike = state.copy(
            waste = state.waste.dropLast(1),
            tableauPiles = newPiles,
            moveCount = state.moveCount + 1
        ))
    }
    checkKlondikeWin()
}

internal fun SolitaireViewModel.klondikeMoveWasteToFoundationImpl(foundationIndex: Int) {
    val state = uiStateInternal.value.klondike ?: return
    if (state.isWon || state.waste.isEmpty()) return
    val card = state.waste.last()
    if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
    saveHistory()
    val newFoundations = state.foundations.toMutableList()
    newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
    uiStateInternal.update {
        it.copy(klondike = state.copy(
            waste = state.waste.dropLast(1),
            foundations = newFoundations,
            moveCount = state.moveCount + 1
        ))
    }
    checkKlondikeWin()
}

internal fun SolitaireViewModel.klondikeMoveTableauToFoundationImpl(fromColumn: Int, foundationIndex: Int) {
    val state = uiStateInternal.value.klondike ?: return
    if (state.isWon) return
    val pile = state.tableauPiles[fromColumn]
    if (pile.faceUp.isEmpty()) return
    val card = pile.faceUp.last()
    if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    val newFaceUp = pile.faceUp.dropLast(1)
    newPiles[fromColumn] = autoFlip(pile.copy(faceUp = newFaceUp))
    val newFoundations = state.foundations.toMutableList()
    newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
    uiStateInternal.update {
        it.copy(klondike = state.copy(
            tableauPiles = newPiles,
            foundations = newFoundations,
            moveCount = state.moveCount + 1
        ))
    }
    checkKlondikeWin()
}

internal fun SolitaireViewModel.klondikeMoveTableauToTableauImpl(fromColumn: Int, cardIndex: Int, toColumn: Int) {
    val state = uiStateInternal.value.klondike ?: return
    if (state.isWon) return
    val fromPile = state.tableauPiles[fromColumn]
    if (cardIndex < 0 || cardIndex >= fromPile.faceUp.size) return
    val movingCards = fromPile.faceUp.subList(cardIndex, fromPile.faceUp.size)
    val topCard = movingCards.first()
    val toPile = state.tableauPiles[toColumn]
    if (!canPlaceOnKlondikeTableau(topCard, toPile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = autoFlip(fromPile.copy(faceUp = fromPile.faceUp.subList(0, cardIndex)))
    newPiles[toColumn] = toPile.copy(faceUp = toPile.faceUp + movingCards)
    uiStateInternal.update {
        it.copy(klondike = state.copy(
            tableauPiles = newPiles,
            moveCount = state.moveCount + 1
        ))
    }
}

/**
 * Animated autocomplete: play the completion computed by
 * [simulateKlondikeAutoComplete] as a rapid cascade of flights to the
 * foundations (one short flight per card, back to back), applying each
 * step's state as its flight lands. History is saved once up front, so
 * the whole cascade stays a single undo step — the same undo behaviour
 * as the instant completion. While the cascade runs, other animated
 * moves, drags, stock draws and undo are ignored (see
 * [SolitaireViewModel.isMoveAnimating]). With no flight geometry
 * available for a step (pile off screen) that step still applies on
 * schedule, just without a visible flight.
 */
internal fun SolitaireViewModel.klondikeAutoCompleteAnimatedImpl() {
    if (isMoveAnimating()) return
    val state = uiStateInternal.value.klondike ?: return
    if (!canKlondikeAutoComplete(state)) return
    val (steps, finalState) = simulateKlondikeAutoComplete(state)
    if (steps.isEmpty()) {
        // Nothing can fly (e.g. only stock to cycle): complete instantly.
        klondikeAutoCompleteImpl()
        return
    }
    saveHistory()
    launchMoveAnimation {
        for (step in steps) {
            val fromRect = cardPositions[step.sourceId]
            val destRect = dropTargets["foundation_${step.foundationIndex}"]
            if (fromRect != null && destRect != null) {
                animatedMoveInternal.value = AnimatedMove(
                    id = nextAnimationId(),
                    cards = listOf(step.card),
                    sourceId = step.sourceId,
                    targetId = "foundation_${step.foundationIndex}",
                    from = fromRect,
                    to = destRect,
                    durationMillis = AUTO_COMPLETE_STEP_MILLIS,
                )
            }
            kotlinx.coroutines.delay(AUTO_COMPLETE_STEP_MILLIS.toLong())
            uiStateInternal.update { it.copy(klondike = step.stateAfter) }
            animatedMoveInternal.value = null
            kotlinx.coroutines.delay(AUTO_COMPLETE_GAP_MILLIS.toLong())
        }
        uiStateInternal.update { it.copy(klondike = finalState) }
        checkKlondikeWin()
    }
}

internal fun SolitaireViewModel.klondikeAutoCompleteImpl() {
    val state = uiStateInternal.value.klondike ?: return
    if (!canKlondikeAutoComplete(state)) return
    saveHistory()
    val (_, finalState) = simulateKlondikeAutoComplete(state)
    uiStateInternal.update { it.copy(klondike = finalState) }
    checkKlondikeWin()
}

/** One card's journey in an autocomplete: from [sourceId] to a foundation. */
internal data class KlondikeAutoCompleteStep(
    val card: Card,
    val sourceId: String,
    val foundationIndex: Int,
    val stateAfter: KlondikeState,
)

/**
 * The full autocomplete for [start], as the ordered single-card moves to
 * the foundations plus the resulting final state. Pure simulation on
 * state values (no UI state is touched), which keeps the sequencing
 * unit-testable; the instant completion applies [Pair.second] directly
 * and the animated one plays [Pair.first] back step by step. Move choice
 * matches the original greedy drain: waste top first, then tableau tops
 * in column order, foundations in index order, drawing one stock card
 * whenever the board stalls (draws are folded into the following step's
 * state, and into the final state when nothing follows them).
 */
internal fun SolitaireViewModel.simulateKlondikeAutoComplete(
    start: KlondikeState,
): Pair<List<KlondikeAutoCompleteStep>, KlondikeState> {
    var current = start
    val steps = mutableListOf<KlondikeAutoCompleteStep>()
    var madeProgress = true
    while (madeProgress) {
        madeProgress = false
        var moved = true
        while (moved) {
            moved = false
            val step = wasteFoundationStep(current) ?: tableauFoundationStep(current)
            if (step != null) {
                steps += step
                current = step.stateAfter
                moved = true
                madeProgress = true
            }
        }
        // Draw one stock card and try again.
        if (current.stock.isNotEmpty()) {
            current = current.copy(
                stock = current.stock.dropLast(1),
                waste = current.waste + current.stock.last()
            )
            madeProgress = true
        }
    }
    return steps to current
}

private fun SolitaireViewModel.wasteFoundationStep(current: KlondikeState): KlondikeAutoCompleteStep? {
    if (current.waste.isEmpty()) return null
    val card = current.waste.last()
    for (fi in current.foundations.indices) {
        if (!canPlaceOnFoundation(card, current.foundations[fi], fi)) continue
        val newFoundations = current.foundations.toMutableList()
        newFoundations[fi] = newFoundations[fi] + card
        return KlondikeAutoCompleteStep(
            card = card,
            sourceId = "waste",
            foundationIndex = fi,
            stateAfter = current.copy(
                waste = current.waste.dropLast(1),
                foundations = newFoundations,
                moveCount = current.moveCount + 1
            ),
        )
    }
    return null
}

private fun SolitaireViewModel.tableauFoundationStep(current: KlondikeState): KlondikeAutoCompleteStep? {
    for (i in current.tableauPiles.indices) {
        val pile = current.tableauPiles[i]
        if (pile.faceUp.isEmpty()) continue
        val card = pile.faceUp.last()
        for (fi in current.foundations.indices) {
            if (!canPlaceOnFoundation(card, current.foundations[fi], fi)) continue
            val newFoundations = current.foundations.toMutableList()
            newFoundations[fi] = newFoundations[fi] + card
            val newPiles = current.tableauPiles.toMutableList()
            newPiles[i] = autoFlip(pile.copy(faceUp = pile.faceUp.dropLast(1)))
            return KlondikeAutoCompleteStep(
                card = card,
                sourceId = "tableau_${i}_${pile.faceUp.size - 1}",
                foundationIndex = fi,
                stateAfter = current.copy(
                    tableauPiles = newPiles,
                    foundations = newFoundations,
                    moveCount = current.moveCount + 1
                ),
            )
        }
    }
    return null
}

/** One fewer recycle left; unlimited (RELAXED) stays unlimited. */
private fun remainingAfterRecycle(redealsRemaining: Int): Int =
    if (redealsRemaining == Int.MAX_VALUE) Int.MAX_VALUE else redealsRemaining - 1

internal fun SolitaireViewModel.canPlaceOnKlondikeTableau(card: Card, pile: TableauPile): Boolean = when {
    pile.faceUp.isEmpty() && pile.faceDown.isEmpty() -> card.rank == Rank.KING
    pile.faceUp.isEmpty() -> false
    else -> pile.faceUp.last().isOneHigherThan(card) && card.alternatesColorWith(pile.faceUp.last())
}

internal fun SolitaireViewModel.canPlaceOnFoundation(
    card: Card,
    foundation: List<Card>,
    foundationIndex: Int,
): Boolean = canPlaceCardOnFoundation(card, foundation, foundationIndex)

/**
 * Whether the Auto-complete button should show for [state] (issue #730).
 *
 * The button used to appear whenever every tableau card was face-up, even when
 * [klondikeAutoCompleteImpl] could not move a single card: stock exhausted with
 * neither the waste top nor any tableau top fitting its foundation. Drawing
 * from the stock always makes progress, so only a stock-empty, move-less board
 * hides the button.
 */
fun canKlondikeAutoComplete(state: KlondikeState): Boolean {
    if (state.isWon) return false
    if (state.tableauPiles.any { it.faceDown.isNotEmpty() }) return false
    if (state.stock.isNotEmpty()) return true
    val wasteTop = state.waste.lastOrNull()
    if (wasteTop != null && state.foundations.indices.any { fi ->
        canPlaceCardOnFoundation(wasteTop, state.foundations[fi], fi)
    }) return true
    return state.tableauPiles.any { pile ->
        val top = pile.faceUp.lastOrNull() ?: return@any false
        state.foundations.indices.any { fi -> canPlaceCardOnFoundation(top, state.foundations[fi], fi) }
    }
}

/** Foundation rule without a ViewModel receiver, so the board can preview it. */
private fun canPlaceCardOnFoundation(
    card: Card,
    foundation: List<Card>,
    foundationIndex: Int,
): Boolean =
    if (foundation.isEmpty()) card.rank == Rank.ACE && card.suit == Suit.entries[foundationIndex]
    else card.suit == foundation.last().suit && card.isOneHigherThan(foundation.last())

internal fun SolitaireViewModel.autoFlip(pile: TableauPile): TableauPile =
    if (pile.faceUp.isEmpty() && pile.faceDown.isNotEmpty())
        pile.copy(faceDown = pile.faceDown.dropLast(1), faceUp = listOf(pile.faceDown.last()))
    else pile

internal fun SolitaireViewModel.checkKlondikeWin() {
    val state = uiStateInternal.value.klondike ?: return
    if (state.foundations.all { it.size == Rank.KING.value }) {
        uiStateInternal.update { it.copy(klondike = state.copy(isWon = true)) }
        onGameWon(GameMode.KLONDIKE, state.elapsedSeconds, state.usedUndo)
    }
}

internal fun SolitaireViewModel.handleKlondikeDropImpl(sourceId: String, targetId: String) {
    when {
        targetId.startsWith("foundation_") -> {
            val fi = targetId.removePrefix("foundation_").toInt()
            if (isSingleCardDrag(sourceId)) {
                when {
                    sourceId == "waste" -> klondikeMoveWasteToFoundation(fi)
                    sourceId.startsWith("tableau_") -> {
                        val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                        klondikeMoveTableauToFoundation(col, fi)
                    }
                }
            }
        }
        targetId.startsWith("tableau_") -> {
            val toCol = targetId.removePrefix("tableau_").toInt()
            when {
                sourceId == "waste" -> klondikeMoveWasteToTableau(toCol)
                sourceId.startsWith("tableau_") -> {
                    val parts = sourceId.removePrefix("tableau_").split("_")
                    val fromCol = parts[0].toInt()
                    val cardIdx = parts.getOrNull(1)?.toInt() ?: return
                    klondikeMoveTableauToTableau(fromCol, cardIdx, toCol)
                }
            }
        }
    }
}

internal fun SolitaireViewModel.klondikeAutoMoveImpl(sourceId: String) {
    planKlondikeAutoMove(sourceId)?.apply?.invoke()
}

/**
 * The auto-move a tap on [sourceId] would make, decided without applying
 * it: the destination pile id plus the move itself. Tap-to-move animation
 * needs the destination (and its on-screen rectangle) before anything
 * moves; the instant path simply applies the plan.
 */
internal fun SolitaireViewModel.planKlondikeAutoMove(sourceId: String): PlannedMove? {
    val state = uiStateInternal.value.klondike ?: return null
    if (state.isWon) return null
    val cards = draggedCards(sourceId)
    if (cards.isEmpty()) return null
    val origin = klondikeMoveOrigin(sourceId) ?: return null
    return planKlondikeFoundation(state, cards, sourceId, origin)
        ?: planKlondikeTableau(state, cards, sourceId, origin)
}

private data class KlondikeMoveOrigin(val fromCol: Int?, val fromIdx: Int)

private fun klondikeMoveOrigin(sourceId: String): KlondikeMoveOrigin? {
    if (sourceId == "waste") return KlondikeMoveOrigin(fromCol = null, fromIdx = 0)
    if (!sourceId.startsWith("tableau_")) return null
    val parts = sourceId.removePrefix("tableau_").split("_")
    return KlondikeMoveOrigin(
        fromCol = parts.getOrNull(0)?.toIntOrNull() ?: return null,
        fromIdx = parts.getOrNull(1)?.toIntOrNull() ?: 0,
    )
}

private fun SolitaireViewModel.planKlondikeFoundation(
    state: KlondikeState,
    cards: List<Card>,
    sourceId: String,
    origin: KlondikeMoveOrigin,
): PlannedMove? {
    // Only a single top card can go up, and it is the preferred move.
    if (cards.size != SINGLE_CARD) return null
    for (fi in state.foundations.indices) {
        if (!canPlaceOnFoundation(cards.first(), state.foundations[fi], fi)) continue
        return when {
            sourceId == "waste" -> PlannedMove("foundation_$fi") { klondikeMoveWasteToFoundation(fi) }
            origin.fromCol != null -> PlannedMove("foundation_$fi") {
                klondikeMoveTableauToFoundation(origin.fromCol, fi)
            }
            else -> return null
        }
    }
    return null
}

private fun SolitaireViewModel.planKlondikeTableau(
    state: KlondikeState,
    cards: List<Card>,
    sourceId: String,
    origin: KlondikeMoveOrigin,
): PlannedMove? {
    val target = state.tableauPiles.indices.firstOrNull { toCol ->
        toCol != origin.fromCol && isViableKlondikeTarget(state, cards, origin, toCol)
    } ?: return null
    return when {
        sourceId == "waste" -> PlannedMove("tableau_$target") { klondikeMoveWasteToTableau(target) }
        origin.fromCol != null -> PlannedMove("tableau_$target") {
            klondikeMoveTableauToTableau(origin.fromCol, origin.fromIdx, target)
        }
        else -> null
    }
}

private fun isLoneKingShuffle(
    state: KlondikeState,
    origin: KlondikeMoveOrigin,
    toPile: TableauPile,
): Boolean {
    if (toPile.faceUp.isNotEmpty() || toPile.faceDown.isNotEmpty()) return false
    if (origin.fromCol == null || origin.fromIdx != 0) return false
    return state.tableauPiles[origin.fromCol].faceDown.isEmpty()
}

private fun SolitaireViewModel.isViableKlondikeTarget(
    state: KlondikeState,
    cards: List<Card>,
    origin: KlondikeMoveOrigin,
    toCol: Int,
): Boolean {
    val toPile = state.tableauPiles[toCol]
    if (!canPlaceOnKlondikeTableau(cards.first(), toPile)) return false
    // Skip shuffling a lone King between empty columns — it accomplishes nothing.
    return !isLoneKingShuffle(state, origin, toPile)
}

internal fun overlapArea(a: Rect, b: Rect): Float {
    val left = maxOf(a.left, b.left)
    val right = minOf(a.right, b.right)
    val top = maxOf(a.top, b.top)
    val bottom = minOf(a.bottom, b.bottom)
    if (left >= right || top >= bottom) return 0f
    return (right - left) * (bottom - top)
}
