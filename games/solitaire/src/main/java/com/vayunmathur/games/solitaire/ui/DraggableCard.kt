package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.platform.SolitaireActions

/**
 * A card the player can pick up. [sourceId] identifies the pile position it comes from; the
 * cards a drag or tap actually carries are derived from live game state by the view model, so
 * nothing here can go stale between recompositions.
 *
 * [pileId] and [index] locate the card in its pile. While the card is part of the stack under
 * the finger, or of a flight still travelling to (or back to) this slot, it is laid out but not
 * drawn — the motion layer draws it instead — so every card is on screen exactly once. It stays
 * laid out so the gesture that carries it keeps running and its position stays registered.
 */
@Composable
fun DraggableCard(
    card: Card,
    sourceId: String,
    pileId: String,
    index: Int,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var startPos by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val drag by actions.dragInfo.collectAsState()
    val flights by actions.flights.collectAsState()
    val hidden = drag?.range?.covers(pileId, index, card) == true ||
        flights.any { it.hides.covers(pileId, index, card) }
    // Feeds the animated auto-complete its launch points; unlaid cards (the stock)
    // stay absent and fly instantly instead.
    LaunchedEffect(card, startPos, cardSize) {
        if (startPos != Offset.Zero) actions.registerCardPosition(card, startPos, cardSize)
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                startPos = coords.positionInRoot()
                cardSize = coords.size
            }
            .pointerInput(card, sourceId) {
                detectTapGestures(
                    onTap = { actions.requestAutoMove(sourceId, startPos, cardSize) },
                )
            }
            .pointerInput(card, sourceId) {
                detectDragGestures(
                    onDragStart = {
                        dragOffset = Offset.Zero
                        actions.startDrag(sourceId, startPos, cardSize)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                        actions.updateDrag(startPos + dragOffset)
                    },
                    onDragEnd = {
                        val center = Offset(cardSize.width / 2f, cardSize.height / 2f)
                        actions.endDrag(startPos + dragOffset + center, cardSize)
                        dragOffset = Offset.Zero
                    },
                    onDragCancel = {
                        actions.cancelDrag()
                        dragOffset = Offset.Zero
                    }
                )
            }
            .graphicsLayer { if (hidden) alpha = 0f }
            // The copy in motion is the one a screen reader should find, not this one.
            .then(if (hidden) Modifier.semantics { hideFromAccessibility() } else Modifier)
    ) {
        content()
    }
}
