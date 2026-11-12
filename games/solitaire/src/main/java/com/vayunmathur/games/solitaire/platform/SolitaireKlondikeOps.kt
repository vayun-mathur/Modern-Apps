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
): String? = tryMoveByDragImpl(sourceId, dropOffset, cardSize)

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
 * Brings the top card of foundation [foundationIndex] back down onto column [toColumn]
 * (standard Klondike: alternate colour, one rank lower, or a King on an empty column).
 * A normal move: one history entry (so undo restores it) and one move counted. Klondike
 * here keeps no score, so there is no foundation-to-tableau penalty to apply.
 */
internal fun SolitaireViewModel.klondikeMoveFoundationToTableauImpl(foundationIndex: Int, toColumn: Int) {
    val state = uiStateInternal.value.klondike ?: return
    if (state.isWon || !canMoveKlondikeFoundationToTableau(state, foundationIndex, toColumn)) return
    val card = state.foundations[foundationIndex].last()
    saveHistory()
    val newFoundations = state.foundations.toMutableList()
    newFoundations[foundationIndex] = newFoundations[foundationIndex].dropLast(1)
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[toColumn] = newPiles[toColumn].let { it.copy(faceUp = it.faceUp + card) }
    uiStateInternal.update {
        it.copy(klondike = state.copy(
            foundations = newFoundations,
            tableauPiles = newPiles,
            moveCount = state.moveCount + 1
        ))
    }
}

/** Whether the top of foundation [foundationIndex] may come down onto column [toColumn]. Pure. */
fun canMoveKlondikeFoundationToTableau(state: KlondikeState, foundationIndex: Int, toColumn: Int): Boolean {
    val card = state.foundations.getOrNull(foundationIndex)?.lastOrNull() ?: return false
    val pile = state.tableauPiles.getOrNull(toColumn) ?: return false
    return canPlaceCardOnKlondikeTableau(card, pile)
}

internal fun SolitaireViewModel.klondikeAutoCompleteImpl() {
    val state = uiStateInternal.value.klondike ?: return
    if (!canKlondikeAutoComplete(state)) return
    saveHistory()
    uiStateInternal.update { it.copy(klondike = runAutoCompleteLoop(state)) }
    checkKlondikeWin()
}

/**
 * The drain-and-draw loop both the instant auto-complete and the skip path share:
 * move every fitting waste/tableau top to its foundation, draw one stock card when
 * stuck, repeat until the stock is spent and nothing moves. Pure, so the animated
 * run stages the same transitions one flight at a time.
 */
internal fun SolitaireViewModel.runAutoCompleteLoop(start: KlondikeState): KlondikeState {
    var current = start
    var madeProgress = true
    while (madeProgress) {
        madeProgress = false
        current = drainToFoundations(current) { current = it; madeProgress = true }
        // Draw one stock card and try again
        if (current.stock.isNotEmpty()) {
            current = current.copy(
                stock = current.stock.dropLast(1),
                waste = current.waste + current.stock.last()
            )
            madeProgress = true
        }
    }
    return current
}

/**
 * Animated auto-complete entry: the button tap (and re-tap) lands here. Merely
 * offering the button locks nothing; only a started run pauses board input, and
 * board input then stops the run ([stopAutoComplete]). Re-tapping the button
 * mid-run skips straight to the finished board; otherwise the run applies one
 * step at a time and flies each foundation hop, continuing when that hop lands
 * ([landFlightImpl]). One history entry covers the whole run, so a single undo
 * restores the pre-run board, and the win is checked once at the end so the
 * overlay cannot pop mid-flight. Flights already in the air keep flying.
 */
internal fun SolitaireViewModel.requestAutoCompleteImpl() {
    if (autoCompleteRunningInternal.value) { skipAutoCompleteToEnd(); return }
    val state = uiStateInternal.value.klondike ?: return
    if (!canKlondikeAutoComplete(state)) return
    saveHistory()
    autoCompleteRunningInternal.value = true
    advanceAutoComplete()
}

/**
 * Steps the run forward from the live state. Each step is applied to game state
 * at once (logical state is authoritative); a foundation hop whose card has a
 * known position launches a fast flight and the run waits for its landing,
 * every other step applies instantly and the loop continues.
 */
internal fun SolitaireViewModel.advanceAutoComplete() {
    while (autoCompleteRunningInternal.value) {
        val current = uiStateInternal.value.klondike ?: break
        val after = moveWasteToFoundation(current) ?: moveTableauToFoundation(current)
        if (after == null && current.stock.isEmpty()) break
        if (after == null) {
            uiStateInternal.update {
                it.copy(klondike = current.copy(
                    stock = current.stock.dropLast(1),
                    waste = current.waste + current.stock.last()
                ))
            }
            continue
        }
        val step = autoCompleteStep(current, after)
        uiStateInternal.update { it.copy(klondike = after) }
        val from = step?.let { cardPositions[it.card] } ?: continue
        val flight = launchFlight(
            cards = listOf(step.card),
            from = from,
            targetId = "foundation_${step.foundationIndex}",
            cardSize = lastCardSize,
            fast = true,
        ) ?: continue
        autoCompleteFlightId = flight.id
        return
    }
    autoCompleteRunningInternal.value = false
    autoCompleteFlightId = null
    checkKlondikeWin()
}

/** One flown step: which card moved and which foundation it took. */
private data class AutoCompleteStep(val card: Card, val foundationIndex: Int)

/** Diffs the grown foundation between [before] and [after] into its step, or null. */
private fun autoCompleteStep(before: KlondikeState, after: KlondikeState): AutoCompleteStep? =
    before.foundations.indices.firstOrNull { fi ->
        after.foundations[fi].size == before.foundations[fi].size + 1
    }?.let { fi -> AutoCompleteStep(after.foundations[fi].last(), fi) }

/**
 * Stops a running auto-complete where it is: every step taken so far stays applied (one
 * undo still restores the pre-run board), the hop in the air lands, and the board is
 * playable again with the offer back if it can still finish. A tap, drag or stock tap
 * on the board mid-run lands here.
 */
internal fun SolitaireViewModel.stopAutoComplete() {
    autoCompleteRunningInternal.value = false
    autoCompleteFlightId = null
}

/**
 * Skips the animation: runs the shared loop to the end on the live state. Hops
 * already in the air are left to land — their moves are applied already.
 */
internal fun SolitaireViewModel.skipAutoCompleteToEnd() {
    autoCompleteRunningInternal.value = false
    autoCompleteFlightId = null
    val state = uiStateInternal.value.klondike ?: return
    uiStateInternal.update { it.copy(klondike = runAutoCompleteLoop(state)) }
    checkKlondikeWin()
}

private fun SolitaireViewModel.drainToFoundations(
    start: KlondikeState,
    onMove: (KlondikeState) -> Unit,
): KlondikeState {
    var current = start
    var moved = true
    while (moved) {
        moved = false
        val afterWaste = moveWasteToFoundation(current)
        if (afterWaste != null) {
            current = afterWaste
            moved = true
            onMove(current)
            continue
        }
        val afterTableau = moveTableauToFoundation(current)
        if (afterTableau != null) {
            current = afterTableau
            moved = true
            onMove(current)
        }
    }
    return current
}

private fun SolitaireViewModel.moveWasteToFoundation(current: KlondikeState): KlondikeState? {
    if (current.waste.isEmpty()) return null
    val card = current.waste.last()
    for (fi in current.foundations.indices) {
        if (!canPlaceOnFoundation(card, current.foundations[fi], fi)) continue
        val newFoundations = current.foundations.toMutableList()
        newFoundations[fi] = newFoundations[fi] + card
        return current.copy(
            waste = current.waste.dropLast(1),
            foundations = newFoundations,
            moveCount = current.moveCount + 1
        )
    }
    return null
}

private fun SolitaireViewModel.moveTableauToFoundation(current: KlondikeState): KlondikeState? {
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
            return current.copy(
                tableauPiles = newPiles,
                foundations = newFoundations,
                moveCount = current.moveCount + 1
            )
        }
    }
    return null
}

/** One fewer recycle left; unlimited (RELAXED) stays unlimited. */
private fun remainingAfterRecycle(redealsRemaining: Int): Int =
    if (redealsRemaining == Int.MAX_VALUE) Int.MAX_VALUE else redealsRemaining - 1

internal fun SolitaireViewModel.canPlaceOnKlondikeTableau(card: Card, pile: TableauPile): Boolean =
    canPlaceCardOnKlondikeTableau(card, pile)

/** Klondike tableau rule: a King on an empty column, else alternate colour one rank lower. Pure. */
fun canPlaceCardOnKlondikeTableau(card: Card, pile: TableauPile): Boolean = when {
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
                sourceId.startsWith(FOUNDATION_PREFIX) -> {
                    val fi = sourceId.removePrefix(FOUNDATION_PREFIX).toIntOrNull() ?: return
                    klondikeMoveFoundationToTableauImpl(fi, toCol)
                }
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
    val targetId = resolveKlondikeAutoMoveTarget(sourceId) ?: return
    handleKlondikeDropImpl(sourceId, targetId)
}

/**
 * The destination [klondikeAutoMoveImpl] would take [sourceId] to — a foundation
 * first, otherwise a viable tableau column — as a drop-target id, or null when
 * there is no legal move. Pure: resolves without moving anything, so both the
 * instant auto-move and the staged flight commit through the same drop handler.
 */
internal fun SolitaireViewModel.resolveKlondikeAutoMoveTarget(sourceId: String): String? {
    val state = uiStateInternal.value.klondike ?: return null
    if (state.isWon) return null
    val cards = draggedCards(sourceId)
    if (cards.isEmpty()) return null
    val origin = klondikeMoveOrigin(sourceId) ?: return null
    // Only a single top card can go up, and it is the preferred move.
    if (cards.size == SINGLE_CARD) {
        for (fi in state.foundations.indices) {
            if (canPlaceOnFoundation(cards.first(), state.foundations[fi], fi)) return "foundation_$fi"
        }
    }
    return state.tableauPiles.indices.firstOrNull { toCol ->
        toCol != origin.fromCol && isViableKlondikeTarget(state, cards, origin, toCol)
    }?.let { "tableau_$it" }
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
