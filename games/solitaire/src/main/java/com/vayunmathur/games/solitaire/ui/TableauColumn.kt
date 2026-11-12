package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.platform.SolitaireActions

@Composable
fun TableauColumn(
    pile: TableauPile,
    columnIndex: Int,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    cardWidth: Dp = CARD_WIDTH,
    cardHeight: Dp = CARD_HEIGHT
) {
    val totalHeight = if (pile.faceDown.isEmpty() && pile.faceUp.isEmpty()) {
        cardHeight
    } else {
        tableauCardOffset(pile.faceDown.size, maxOf(0, pile.faceUp.size - 1)) + cardHeight
    }
    val pileId = "tableau_$columnIndex"

    DropTarget(pileId, actions, modifier) {
        Box(modifier = Modifier.heightIn(min = totalHeight)) {
            // Drawn under the cards too, so a column whose every card is being carried
            // or is in the air reads as the empty column it is about to be.
            if (pile.faceDown.isEmpty()) {
                EmptySlot(cardWidth = cardWidth, cardHeight = cardHeight)
            }
            pile.faceDown.indices.forEach { i ->
                CardBack(
                    modifier = Modifier.offset(y = tableauCardOffset(i, 0)),
                    cardWidth = cardWidth,
                    cardHeight = cardHeight
                )
            }
            pile.faceUp.forEachIndexed { index, card ->
                DraggableCard(
                    card = card,
                    sourceId = "${pileId}_$index",
                    pileId = pileId,
                    index = index,
                    actions = actions,
                    modifier = Modifier.offset(y = tableauCardOffset(pile.faceDown.size, index)),
                ) {
                    CardFace(
                        card = card,
                        cardWidth = cardWidth,
                        cardHeight = cardHeight
                    )
                }
            }
        }
    }
}
