package com.vayunmathur.games.solitaire.platform

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameMode

/**
 * A tap-to-move travelling to its destination.
 *
 * The cards keep living in game state until the flight lands: [commitFlightImpl]
 * performs the move through the same drop handlers a drag ends in, so legality is
 * re-validated at landing and a stale flight (state moved on underneath it) is a
 * no-op rather than a corrupt move. [from] is the tap point in root coordinates;
 * the overlay translates it into its own frame and derives the landing point from
 * the target's registered drop rectangle.
 */
data class CardFlight(
    val cards: List<Card>,
    val sourceId: String,
    val targetId: String,
    val from: Offset,
    /** Fast auto-complete hops fly shorter than a tap-to-move. */
    val fast: Boolean = false,
)

/**
 * Stages a flight for the card (or valid run) at [sourceId], resolving the same
 * destination [autoMoveImpl] would take it to. False when there is no legal move,
 * when [sourceId] holds no card, or when a flight is already in the air.
 */
internal fun SolitaireViewModel.requestFlightImpl(sourceId: String, startPos: Offset): Boolean {
    if (flightInfoInternal.value != null) return false
    val targetId = when (uiStateInternal.value.gameMode) {
        GameMode.KLONDIKE -> resolveKlondikeAutoMoveTarget(sourceId)
        GameMode.FREECELL -> resolveFreeCellAutoMoveTarget(sourceId)
        else -> null // Spider has no foundations; Pyramid is already tap-based.
    } ?: return false
    val cards = draggedCards(sourceId)
    if (cards.isEmpty()) return false
    flightInfoInternal.value = CardFlight(cards, sourceId, targetId, startPos)
    return true
}

/**
 * Lands the staged flight. Clears it first so the overlay lifts the moment the
 * move commits; a flight whose source or target no longer applies is refused by
 * the move functions themselves. A fast flight landing mid-run advances the
 * auto-complete instead of going through the drop handlers.
 */
internal fun SolitaireViewModel.commitFlightImpl() {
    val flight = flightInfoInternal.value ?: return
    flightInfoInternal.value = null
    if (flight.fast && autoCompleteRunningInternal.value &&
        uiStateInternal.value.gameMode == GameMode.KLONDIKE
    ) {
        landAutoCompleteStep(flight)
        return
    }
    when (uiStateInternal.value.gameMode) {
        GameMode.KLONDIKE -> handleKlondikeDropImpl(flight.sourceId, flight.targetId)
        GameMode.FREECELL -> handleFreeCellDropImpl(flight.sourceId, flight.targetId)
        else -> {}
    }
}

internal fun SolitaireViewModel.cancelFlightImpl() {
    flightInfoInternal.value = null
}

/** Drops any staged flight: undo, restart, mode switch and give-up all strand it. */
internal fun SolitaireViewModel.clearFlight() {
    flightInfoInternal.value = null
    // An abandoned run strands nothing: undo/give-up/mode-switch restore or
    // replace the board, and the single pre-run history entry covers undo.
    autoCompleteRunningInternal.value = false
}
