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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.Suit
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
    val selectedSource by actions.selectedSourceId.collectAsState()
    val isSelected = selectedSource == sourceId
    val hasSelection = selectedSource != null
    // TalkBack identity of this (always face-up) card and the actions its menu offers.
    val description = stringResource(
        R.string.card_face_up, stringResource(rankStringRes(card.rank)), stringResource(suitStringRes(card.suit)),
    )
    val autoMoveLabel = stringResource(R.string.card_action_auto_move)
    val foundationLabel = stringResource(R.string.card_action_move_to_foundation)
    val selectLabel = stringResource(if (isSelected) R.string.card_action_deselect else R.string.card_action_select)
    val moveHereLabel = stringResource(R.string.card_action_move_selected_here)
    val selectedLabel = stringResource(R.string.card_selected)
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
            // Otherwise the card is a TalkBack stop in its own right: double-tap does
            // exactly what a touch tap does (requestAutoMove), and the custom actions
            // cover the moves a drag would make — foundation first, or select this
            // card / drop the selected card onto this pile. Drag gestures above are
            // untouched; these semantics actions invoke the same ViewModel calls.
            .semantics {
                if (hidden) {
                    hideFromAccessibility()
                } else {
                    contentDescription = description
                    if (isSelected) {
                        selected = true
                        stateDescription = selectedLabel
                    }
                    onClick(autoMoveLabel) { actions.requestAutoMove(sourceId, startPos, cardSize) }
                    customActions = buildList {
                        // The same tap handler: it tries a foundation first, then the
                        // best tableau/free-cell spot, and is a no-op (returns false)
                        // where neither is legal — Spider has no foundations at all.
                        add(CustomAccessibilityAction(foundationLabel) {
                            actions.requestAutoMove(sourceId, startPos, cardSize)
                        })
                        add(CustomAccessibilityAction(selectLabel) { actions.selectCard(sourceId); true })
                        if (hasSelection && !isSelected && pileId != WASTE_PILE_ID) {
                            add(CustomAccessibilityAction(moveHereLabel) {
                                actions.moveSelectedCardTo(pileId)
                            })
                        }
                    }
                }
            }
    ) {
        content()
    }
}

/** Pile id of the Klondike waste, which is a source but never a drop target. */
private const val WASTE_PILE_ID = "waste"

private fun rankStringRes(rank: Rank): Int = when (rank) {
    Rank.ACE -> R.string.rank_ace
    Rank.TWO -> R.string.rank_two
    Rank.THREE -> R.string.rank_three
    Rank.FOUR -> R.string.rank_four
    Rank.FIVE -> R.string.rank_five
    Rank.SIX -> R.string.rank_six
    Rank.SEVEN -> R.string.rank_seven
    Rank.EIGHT -> R.string.rank_eight
    Rank.NINE -> R.string.rank_nine
    Rank.TEN -> R.string.rank_ten
    Rank.JACK -> R.string.rank_jack
    Rank.QUEEN -> R.string.rank_queen
    Rank.KING -> R.string.rank_king
}

private fun suitStringRes(suit: Suit): Int = when (suit) {
    Suit.HEARTS -> R.string.suit_hearts
    Suit.DIAMONDS -> R.string.suit_diamonds
    Suit.SPADES -> R.string.suit_spades
    Suit.CLUBS -> R.string.suit_clubs
}
