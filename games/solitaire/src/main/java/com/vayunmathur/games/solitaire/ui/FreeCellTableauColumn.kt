package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.platform.SolitaireActions

@Composable
fun FreeCellTableauColumn(
    pile: List<Card>,
    columnIndex: Int,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    cardWidth: Dp = CARD_WIDTH,
    cardHeight: Dp = CARD_HEIGHT
) {
    val totalHeight = tableauCardOffset(0, maxOf(0, pile.size - 1)) + cardHeight
    val pileId = "tableau_$columnIndex"

    DropTarget(pileId, actions, modifier) {
        Box(modifier = Modifier.heightIn(min = totalHeight)) {
            EmptySlot(cardWidth = cardWidth, cardHeight = cardHeight)
            pile.forEachIndexed { index, card ->
                DraggableCard(
                    card = card,
                    sourceId = "${pileId}_$index",
                    pileId = pileId,
                    index = index,
                    actions = actions,
                    modifier = Modifier.offset(y = tableauCardOffset(0, index)),
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
