package com.vayunmathur.games.solitaire.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.games.solitaire.data.Card
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The UI contract between [SolitaireViewModel] and the boards.
 *
 * The boards and the card widgets they share take this interface rather than the ViewModel
 * itself, so they can be rendered by a `@Preview` — which is what the store listing images
 * are generated from. It lives in `platform` rather than next to the boards so the dependency
 * runs one way: `ui` depends on `platform`, and the ViewModel implements this.
 *
 * Every member has a no-op default, so [Noop] is the whole implementation a preview needs.
 */
interface SolitaireActions {

    /** The drag in flight, or null. A preview has none, which is why the default is empty. */
    val dragInfo: StateFlow<DragInfo?> get() = NoDrag

    /**
     * A tap-to-move travelling to its destination, or null. The overlay animates it;
     * the move itself commits only when the flight lands.
     */
    val flightInfo: StateFlow<CardFlight?> get() = NoFlight

    /** Drop zones, registered by the piles as they are laid out. */
    val dropTargets: MutableMap<String, Rect> get() = DiscardedDropTargets

    /** True while an animated auto-complete run is in flight. A preview never runs one. */
    val autoCompleteRunning: StateFlow<Boolean> get() = NoAutoComplete

    /**
     * Records where [card] was last laid out in root coordinates, so the animated
     * auto-complete can fly it from there. Positions for cards never laid out (in
     * the stock) are simply absent, and those steps apply instantly.
     */
    fun registerCardPosition(card: Card, position: Offset) {}

    fun startDrag(sourceId: String, startPos: Offset, cardSize: IntSize = IntSize.Zero): Boolean = false
    fun updateDrag(offset: Offset) {}
    fun endDrag(dropOffset: Offset, cardSize: IntSize = IntSize.Zero) {}
    fun cancelDrag() {}

    fun drawFromStock() {}
    fun klondikeAutoComplete() {}
    fun dealSpiderStock() {}
    fun pyramidTapCard(id: String) {}
    fun pyramidDealStock() {}

    /**
     * Tap-to-move: send the card (or valid run) at [sourceId] to its best legal
     * destination — a foundation first, otherwise a valid tableau/free-cell spot.
     * A no-op when there is no legal move. Klondike and FreeCell only; Spider has no
     * foundations and Pyramid is already tap-based.
     */
    fun autoMove(sourceId: String) {}

    /**
     * Animated tap-to-move: resolves the best legal destination for [sourceId] and
     * stages the flight from [startPos], which the overlay animates before committing.
     * Returns false (and stages nothing) when there is no legal move or a flight is
     * already active. Klondike and FreeCell only, like [autoMove].
     */
    fun requestAutoMove(sourceId: String, startPos: Offset): Boolean = false

    /** Lands the staged flight, performing its move. A no-op without one. */
    fun commitFlight() {}

    /** Drops the staged flight without moving anything. */
    fun cancelFlight() {}

    fun undo() {}
    fun restart() {}
    fun giveUp() {}

    companion object {
        val Noop: SolitaireActions = object : SolitaireActions {}
    }
}

private val NoDrag: StateFlow<DragInfo?> = MutableStateFlow(null)
private val NoFlight: StateFlow<CardFlight?> = MutableStateFlow(null)
private val NoAutoComplete: StateFlow<Boolean> = MutableStateFlow(false)

/** Somewhere for a [SolitaireActions.Noop] board to register its drop zones and forget them. */
private val DiscardedDropTargets: MutableMap<String, Rect> = mutableMapOf()

