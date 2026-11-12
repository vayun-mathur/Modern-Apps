package com.vayunmathur.games.solitaire.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import kotlinx.coroutines.flow.update

// ---- Shared: drag, drop, auto-move, drag state ----
// Moved from SolitaireViewModel.kt (FileLength split); behavior identical.

/**
 * The cards a drag from [sourceId] carries, read from the live state rather than
 * from whatever the UI captured when the gesture was set up. Empty when the source
 * no longer holds a card (a stale slot), which is the signal not to start a drag.
 */
internal fun SolitaireViewModel.draggedCards(sourceId: String): List<Card> {
    val state = uiStateInternal.value
    return when {
        sourceId == "waste" -> state.klondike?.waste?.lastOrNull()?.let { listOf(it) } ?: emptyList()
        sourceId.startsWith("freecell_") -> freeCellDragCards(state, sourceId)
        sourceId.startsWith("tableau_") -> tableauDragCards(state, sourceId)
        else -> emptyList()
    }
}

private fun freeCellDragCards(state: SolitaireUiState, sourceId: String): List<Card> {
    val ci = sourceId.removePrefix("freecell_").toIntOrNull() ?: return emptyList()
    return state.freeCell?.freeCells?.getOrNull(ci)?.let { listOf(it) } ?: emptyList()
}

private fun tableauDragCards(state: SolitaireUiState, sourceId: String): List<Card> {
    val parts = sourceId.removePrefix("tableau_").split("_")
    val col = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
    val idx = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
    val faceUp = tableauFaceUp(state, col) ?: return emptyList()
    return if (idx in faceUp.indices) faceUp.subList(idx, faceUp.size) else emptyList()
}

private fun tableauFaceUp(state: SolitaireUiState, col: Int): List<Card>? = when (state.gameMode) {
    GameMode.KLONDIKE -> state.klondike?.tableauPiles?.getOrNull(col)?.faceUp
    GameMode.SPIDER -> state.spider?.tableauPiles?.getOrNull(col)?.faceUp
    GameMode.FREECELL -> state.freeCell?.tableauPiles?.getOrNull(col)
    else -> null
}

/** A foundation only ever accepts a drag that carries the single top card. */
internal fun SolitaireViewModel.isSingleCardDrag(sourceId: String): Boolean = draggedCards(sourceId).size == 1

/**
 * Drops the stack from [sourceId] on the first candidate target that accepts it. Returns the
 * accepting target's id, or null when no target took the move (nothing changed).
 */
internal fun SolitaireViewModel.tryMoveByDragImpl(
    sourceId: String,
    dropOffset: Offset,
    cardSize: IntSize = IntSize.Zero,
): String? {
    val mode = uiStateInternal.value.gameMode ?: return null
    if (mode == GameMode.PYRAMID) return null // Pyramid is tap-based, not drag-based.
    for (targetId in candidateDropTargets(dropOffset, cardSize)) {
        val before = uiStateInternal.value
        applyDrop(sourceId, targetId)
        if (uiStateInternal.value !== before) return targetId
    }
    return null
}

/**
 * The drop targets the leading (top) card of the dragged stack is over, best first.
 *
 * Ranking is by overlap area of the leading card's bounds rather than by which rect
 * contains the pointer: dropping a large tableau stack is done by its bottom, so the
 * pointer-derived centre can fall outside a short destination column (issue #505).
 *
 * All of them are returned, not just the winner, because a pile that overlaps the card
 * most is not necessarily one that can take it — an empty column's rect is only one card
 * tall while its neighbours grow with every card in them, so a tall neighbour that cannot
 * legally accept the run outscores the empty column the player is aiming at. Trying the
 * candidates in order lets [tryMoveByDrag] fall through to the first that accepts the
 * move instead of silently discarding it (issue #628).
 */
internal fun SolitaireViewModel.candidateDropTargets(dropOffset: Offset, cardSize: IntSize): List<String> {
    if (cardSize.width > 0 && cardSize.height > 0) {
        val w = cardSize.width.toFloat()
        val h = cardSize.height.toFloat()
        val cardRect = Rect(dropOffset.x - w / 2f, dropOffset.y - h / 2f, dropOffset.x + w / 2f, dropOffset.y + h / 2f)
        val overlapping = dropTargets
            .mapNotNull { (id, rect) -> overlapArea(cardRect, rect).takeIf { it > 0f }?.let { id to it } }
            .sortedByDescending { it.second }
        if (overlapping.isNotEmpty()) return overlapping.map { it.first }
    }
    // Fallback for a zero-size card: any rect containing the centre point.
    return dropTargets.entries.filter { (_, rect) -> rect.contains(dropOffset) }.map { it.key }
}

// --- Tap to move (auto) ---

internal fun SolitaireViewModel.autoMoveImpl(sourceId: String) {
    when (uiStateInternal.value.gameMode) {
        GameMode.KLONDIKE -> klondikeAutoMoveImpl(sourceId)
        GameMode.FREECELL -> freeCellAutoMoveImpl(sourceId)
        else -> {} // Spider has no foundations; Pyramid is already tap-based.
    }
}

/**
 * Starts a drag from [sourceId], deriving the carried cards from current state.
 * Returns false (and starts nothing) when that source holds no card.
 */
internal fun SolitaireViewModel.startDragImpl(sourceId: String, startPos: Offset, cardSize: IntSize): Boolean {
    val range = sourceRange(sourceId)
    // Cards still in the air cannot be picked up: the hand would grab cards the
    // player cannot see where they are drawn. Other flights keep flying.
    if (range == null || isAirborne(range)) {
        dragInfoInternal.value = null
        return false
    }
    dragInfoInternal.value = DragInfo(
        cards = range.cards,
        sourceId = sourceId,
        offset = startPos,
        startPos = startPos,
        cardSize = cardSize,
        pileId = range.pileId,
        startIndex = range.start,
    )
    return true
}

internal fun SolitaireViewModel.updateDragImpl(offset: Offset) {
    dragInfoInternal.update { it?.copy(offset = offset) }
}

/**
 * Drops the carried stack. An accepted drop is applied at once and the stack flies from under
 * the finger to the slot it now occupies; a rejected one flies back to where it was picked up.
 * Either way the stack is handed from the drag to a flight in the same update, so the cards
 * are hidden in their pile until that flight lands and reappear there exactly once.
 */
internal fun SolitaireViewModel.endDragImpl(dropOffset: Offset, cardSize: IntSize) {
    val info = dragInfoInternal.value ?: return
    // Use the cardSize recorded at drag start (finger owns that card), with the
    // current drop size as fallback so call sites lacking a size still resolve.
    val effectiveSize = if (cardSize.width > 0 && cardSize.height > 0) cardSize else info.cardSize
    val targetId = tryMoveByDrag(info.sourceId, dropOffset, effectiveSize)
    if (targetId != null) {
        launchFlight(info.cards, info.offset, targetId, info.cardSize)
    } else {
        returnCarriedCards(info)
    }
    dragInfoInternal.value = null
}

/** The gesture was cancelled: the stack goes back where it came from. */
internal fun SolitaireViewModel.cancelDragImpl() {
    dragInfoInternal.value?.let { returnCarriedCards(it) }
    dragInfoInternal.value = null
}

/** Flies an undropped stack home, if it is still where it was picked up. */
private fun SolitaireViewModel.returnCarriedCards(info: DragInfo) {
    if (sourceRange(info.sourceId) != info.range) return
    launchReturnFlight(info.range, info.offset, info.startPos, info.cardSize)
}
