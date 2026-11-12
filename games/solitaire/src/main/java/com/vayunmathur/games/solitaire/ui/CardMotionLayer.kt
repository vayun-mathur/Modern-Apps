package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.platform.CardFlight
import com.vayunmathur.games.solitaire.platform.FlightTarget
import com.vayunmathur.games.solitaire.platform.SolitaireActions
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.travelledOffset

/** One auto-complete hop: snappy enough to stagger, slow enough to follow. */
private const val FAST_FLIGHT_MILLIS = 110

/** Above every pile, the board's controls and the scrolling column that holds them. */
private const val MOTION_LAYER_Z = 1f

/**
 * Every card in motion — the stack under the finger and every flight — drawn in one layer laid
 * over the whole game screen.
 *
 * It lives outside the board's scrolling column rather than inside a pile, so nothing in motion
 * is clipped by a pile's bounds or by the scroll viewport, and it works in root coordinates so a
 * card can travel between any two piles. Each flight is keyed by its id and runs on its own
 * clock: a new flight never restarts or jumps one already in the air. A flight lands on the slot
 * its card now occupies, positioned by [pileSlotPosition] — the same layout the piles draw
 * with — and is reported back through [SolitaireActions.landFlight] so the pile shows the card
 * again. A flight whose target pile is not laid out (a stale flight after a mode switch) lands
 * at once instead of stranding cards mid-air.
 */
@Composable
fun CardMotionLayer(actions: SolitaireActions, modifier: Modifier = Modifier) {
    val flights by actions.flights.collectAsState()
    val drag by actions.dragInfo.collectAsState()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(MOTION_LAYER_Z)
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        flights.forEach { flight ->
            key(flight.id) { FlyingCards(flight, actions) { origin } }
        }
        // The stack under the finger, drawn last so it passes over everything else.
        drag?.let { carried ->
            CarriedCards(
                cards = carried.cards,
                cardSize = carried.cardSize,
                modifier = Modifier.offset { (carried.offset - origin).round() },
            )
        }
    }
}

@Composable
private fun FlyingCards(flight: CardFlight, actions: SolitaireActions, origin: () -> Offset) {
    val density = LocalDensity.current
    val to = when (val target = flight.target) {
        is FlightTarget.Point -> target.position
        is FlightTarget.Slot -> actions.dropTargets[target.pileId]?.let { pile ->
            with(density) { pileSlotPosition(pile.topLeft, target) }
        }
    }
    if (to == null) {
        LaunchedEffect(flight.id) { actions.landFlight(flight.id) }
        return
    }
    val spec = if (flight.fast) Motion.over<Offset>(FAST_FLIGHT_MILLIS) else Motion.drop()
    val position = travelledOffset(flight.id, flight.from, to, spec) { actions.landFlight(flight.id) }
    CarriedCards(
        cards = flight.cards,
        cardSize = flight.cardSize,
        modifier = Modifier.offset { (position.value - origin()).round() },
    )
}

/** A carried run, fanned like a tableau column, each card drawn exactly once. */
@Composable
private fun CarriedCards(cards: List<Card>, cardSize: IntSize, modifier: Modifier) {
    val density = LocalDensity.current
    val (width, height) = with(density) {
        if (cardSize.width > 0 && cardSize.height > 0) {
            cardSize.width.toDp() to cardSize.height.toDp()
        } else {
            CARD_WIDTH to CARD_HEIGHT
        }
    }
    Box(modifier) {
        cards.forEachIndexed { index, card ->
            CardFace(
                card = card,
                modifier = Modifier.offset(y = tableauCardOffset(0, index)),
                cardWidth = width,
                cardHeight = height,
            )
        }
    }
}
