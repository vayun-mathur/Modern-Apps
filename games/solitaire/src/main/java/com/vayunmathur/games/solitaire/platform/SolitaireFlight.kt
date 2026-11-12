package com.vayunmathur.games.solitaire.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameMode
import kotlinx.coroutines.flow.update

/**
 * A run of [cards] starting at [start] in pile [pileId].
 *
 * Index semantics follow the drag source ids: a Klondike/Spider tableau index counts face-up
 * cards only, a FreeCell column is flat, the waste and a foundation index their own lists, and a
 * free cell is always 0. Matching on the card as well as the slot keeps a stale range (the state
 * moved on underneath it) from hiding whatever card now sits in that slot — and keeps Spider's
 * duplicate cards apart, which identity alone cannot.
 */
data class PileRange(val pileId: String, val start: Int, val cards: List<Card>) {

    /** Whether the card drawn at [index] of [pileId] is [card] and part of this run. */
    fun covers(pileId: String, index: Int, card: Card): Boolean =
        pileId == this.pileId && (index - start) in cards.indices && cards[index - start] == card

    /** Whether the two runs share a slot. */
    fun overlaps(other: PileRange): Boolean =
        pileId == other.pileId && start < other.start + other.cards.size && other.start < start + cards.size
}

/** Where a flight lands. */
sealed interface FlightTarget {

    /**
     * The slot the leading card occupies in its pile once the move has been applied. The board
     * turns it into a position with the same layout function it draws that slot with, so a
     * flight cannot land somewhere the card is not then drawn.
     */
    data class Slot(val pileId: String, val faceDownCount: Int, val index: Int) : FlightTarget

    /** A fixed root position: a rejected drag going back to where it was picked up. */
    data class Point(val position: Offset) : FlightTarget
}

/**
 * Cards travelling across the board. Purely visual: the move a flight shows has already been
 * applied to game state when the flight is launched, so the logical board never waits on an
 * animation and several flights can be in the air at once, each keyed by [id].
 *
 * While it flies, the board hides the cards in [hides] — where they now live in game state — so
 * each card is drawn exactly once: by the flight until it lands, then by its pile. [from] is the
 * leading card's top-left in root coordinates.
 */
data class CardFlight(
    val id: Long,
    val cards: List<Card>,
    val from: Offset,
    val target: FlightTarget,
    val hides: PileRange,
    val cardSize: IntSize = IntSize.Zero,
    /** Auto-complete hops fly shorter than a tap-to-move. */
    val fast: Boolean = false,
)

/** A tap on cards that were still in the air, replayed once they land. */
internal data class QueuedTap(val sourceId: String, val card: Card)

/**
 * The run a tap or drag on [sourceId] would carry, located in its pile; null when the source
 * holds no card.
 */
internal fun SolitaireViewModel.sourceRange(sourceId: String): PileRange? {
    val cards = draggedCards(sourceId)
    if (cards.isEmpty()) return null
    val state = uiStateInternal.value
    return when {
        sourceId == WASTE -> state.klondike?.waste?.let { PileRange(WASTE, it.lastIndex, cards) }
        sourceId.startsWith(FREECELL_PREFIX) -> PileRange(sourceId, 0, cards)
        sourceId.startsWith(FOUNDATION_PREFIX) -> {
            val fi = sourceId.removePrefix(FOUNDATION_PREFIX).toIntOrNull()
            currentFoundations()?.getOrNull(fi ?: -1)?.let { PileRange(sourceId, it.lastIndex, cards) }
        }
        sourceId.startsWith(TABLEAU_PREFIX) -> {
            val parts = sourceId.removePrefix(TABLEAU_PREFIX).split("_")
            val col = parts.getOrNull(0)?.toIntOrNull()
            val idx = parts.getOrNull(1)?.toIntOrNull()
            if (col == null || idx == null) null else PileRange("$TABLEAU_PREFIX$col", idx, cards)
        }
        else -> null
    }
}

/** Whether any card of [range] is still in the air. */
internal fun SolitaireViewModel.isAirborne(range: PileRange): Boolean =
    flightsInternal.value.any { it.hides.overlaps(range) }

private fun SolitaireViewModel.currentFoundations(): List<List<Card>>? = with(uiStateInternal.value) {
    when (gameMode) {
        GameMode.KLONDIKE -> klondike?.foundations
        GameMode.FREECELL -> freeCell?.foundations
        else -> null
    }
}

/**
 * Tap-to-move: resolves the destination [autoMoveImpl] would take [sourceId] to, applies the
 * move at once and launches its flight from [startPos]. A tap on cards still in the air is
 * queued and replayed when they land, so it can neither jump nor cancel the flight carrying
 * them. False when there is no legal move or [sourceId] holds no card.
 */
internal fun SolitaireViewModel.requestFlightImpl(sourceId: String, startPos: Offset, cardSize: IntSize): Boolean {
    val range = sourceRange(sourceId) ?: return false
    if (isAirborne(range)) {
        val tap = QueuedTap(sourceId, range.cards.first())
        if (tap !in queuedTaps) queuedTaps.addLast(tap)
        return true
    }
    val targetId = resolveAutoMoveTarget(sourceId) ?: return false
    return flyMove(sourceId, targetId, range.cards, startPos, cardSize)
}

private fun SolitaireViewModel.resolveAutoMoveTarget(sourceId: String): String? =
    when (uiStateInternal.value.gameMode) {
        GameMode.KLONDIKE -> resolveKlondikeAutoMoveTarget(sourceId)
        GameMode.FREECELL -> resolveFreeCellAutoMoveTarget(sourceId)
        else -> null // Spider has no foundations; Pyramid is already tap-based.
    }

/**
 * Applies the move of [cards] from [sourceId] onto [targetId] through the same drop handler a
 * drag ends in, then — if it was legal — launches a flight from [from] (no flight when the
 * position is unknown). Returns whether the move was applied.
 */
internal fun SolitaireViewModel.flyMove(
    sourceId: String,
    targetId: String,
    cards: List<Card>,
    from: Offset?,
    cardSize: IntSize,
): Boolean {
    val before = uiStateInternal.value
    applyDrop(sourceId, targetId)
    if (uiStateInternal.value === before) return false
    if (from != null) launchFlight(cards, from, targetId, cardSize)
    return true
}

/** Dispatches a move to the current mode's drop handler; illegal moves leave state untouched. */
internal fun SolitaireViewModel.applyDrop(sourceId: String, targetId: String) {
    when (uiStateInternal.value.gameMode) {
        GameMode.KLONDIKE -> handleKlondikeDropImpl(sourceId, targetId)
        GameMode.SPIDER -> handleSpiderDropImpl(sourceId, targetId)
        GameMode.FREECELL -> handleFreeCellDropImpl(sourceId, targetId)
        GameMode.PYRAMID, null -> {}
    }
}

/**
 * Launches a flight of [cards] — already moved onto [targetId] in game state — from [from] to
 * the slot they now occupy there. Returns the flight, or null when the target is not a pile.
 */
internal fun SolitaireViewModel.launchFlight(
    cards: List<Card>,
    from: Offset,
    targetId: String,
    cardSize: IntSize,
    fast: Boolean = false,
): CardFlight? {
    val slot = landingSlot(targetId, cards) ?: return null
    val flight = CardFlight(
        id = nextFlightId++,
        cards = cards,
        from = from,
        target = slot,
        hides = PileRange(slot.pileId, slot.index, cards),
        cardSize = if (cardSize.width > 0 && cardSize.height > 0) cardSize else lastCardSize,
        fast = fast,
    )
    flightsInternal.update { it + flight }
    return flight
}

/** Launches a flight returning [cards] (never moved) from [from] to [home], hidden at [range]. */
internal fun SolitaireViewModel.launchReturnFlight(range: PileRange, from: Offset, home: Offset, cardSize: IntSize) {
    val flight = CardFlight(
        id = nextFlightId++,
        cards = range.cards,
        from = from,
        target = FlightTarget.Point(home),
        hides = range,
        cardSize = if (cardSize.width > 0 && cardSize.height > 0) cardSize else lastCardSize,
    )
    flightsInternal.update { it + flight }
}

/**
 * The slot the leading card of [cards] occupies on [targetId] in the current (post-move) state.
 * A pile that no longer ends with the cards (a Spider run that completed and cleared) still
 * yields its next slot, and the flight's hidden range then matches nothing.
 */
internal fun SolitaireViewModel.landingSlot(targetId: String, cards: List<Card>): FlightTarget.Slot? {
    val state = uiStateInternal.value
    return when {
        targetId.startsWith(FOUNDATION_PREFIX) -> {
            val fi = targetId.removePrefix(FOUNDATION_PREFIX).toIntOrNull() ?: return null
            val pile = currentFoundations()?.getOrNull(fi) ?: return null
            FlightTarget.Slot(targetId, 0, (pile.size - cards.size).coerceAtLeast(0))
        }
        targetId.startsWith(FREECELL_PREFIX) -> FlightTarget.Slot(targetId, 0, 0)
        targetId.startsWith(TABLEAU_PREFIX) -> {
            val col = targetId.removePrefix(TABLEAU_PREFIX).toIntOrNull() ?: return null
            val (faceDown, faceUp) = when (state.gameMode) {
                GameMode.KLONDIKE -> state.klondike?.tableauPiles?.getOrNull(col)?.let { it.faceDown.size to it.faceUp.size }
                GameMode.SPIDER -> state.spider?.tableauPiles?.getOrNull(col)?.let { it.faceDown.size to it.faceUp.size }
                GameMode.FREECELL -> state.freeCell?.tableauPiles?.getOrNull(col)?.let { 0 to it.size }
                else -> null
            } ?: return null
            FlightTarget.Slot(targetId, faceDown, (faceUp - cards.size).coerceAtLeast(0))
        }
        else -> null
    }
}

/**
 * A flight reached its destination: the board draws its cards in their pile again. The landing
 * of the auto-complete's current hop advances the run; queued taps whose cards are now down are
 * replayed against the live state.
 */
internal fun SolitaireViewModel.landFlightImpl(id: Long) {
    if (flightsInternal.value.none { it.id == id }) return
    flightsInternal.update { flights -> flights.filterNot { it.id == id } }
    if (id == autoCompleteFlightId) {
        autoCompleteFlightId = null
        if (autoCompleteRunningInternal.value) advanceAutoComplete()
    }
    replayQueuedTaps()
}

private fun SolitaireViewModel.replayQueuedTaps() {
    if (queuedTaps.isEmpty()) return
    val pending = queuedTaps.toList()
    queuedTaps.clear()
    for (tap in pending) {
        val range = sourceRange(tap.sourceId)
        // The state moved on under the queued tap: the card it was aimed at is gone.
        if (range == null || range.cards.first() != tap.card) continue
        if (isAirborne(range)) { queuedTaps.addLast(tap); continue }
        val targetId = resolveAutoMoveTarget(tap.sourceId) ?: continue
        flyMove(tap.sourceId, targetId, range.cards, cardPositions[tap.card], lastCardSize)
    }
}

/** Drops every flight and queued tap: undo, restart, mode switch and give-up replace the board. */
internal fun SolitaireViewModel.clearMotion() {
    flightsInternal.value = emptyList()
    queuedTaps.clear()
    dragInfoInternal.value = null
    autoCompleteFlightId = null
    // An abandoned run strands nothing: undo/give-up/mode-switch restore or
    // replace the board, and the single pre-run history entry covers undo.
    autoCompleteRunningInternal.value = false
}

internal const val WASTE = "waste"
internal const val TABLEAU_PREFIX = "tableau_"
internal const val FOUNDATION_PREFIX = "foundation_"
internal const val FREECELL_PREFIX = "freecell_"
