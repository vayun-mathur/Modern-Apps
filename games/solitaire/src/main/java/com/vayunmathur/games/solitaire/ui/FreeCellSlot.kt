package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.platform.SolitaireActions

/** A FreeCell cell. The empty outline stays drawn under the card, which can be hidden mid-move. */
@Composable
fun FreeCellSlot(
    card: Card?,
    index: Int,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    cardWidth: Dp = CARD_WIDTH,
    cardHeight: Dp = CARD_HEIGHT
) {
    val pileId = "freecell_$index"
    DropTarget(pileId, actions, modifier) {
        Box {
            EmptySlot(cardWidth = cardWidth, cardHeight = cardHeight)
            if (card != null) {
                DraggableCard(
                    card = card,
                    sourceId = pileId,
                    pileId = pileId,
                    index = 0,
                    actions = actions,
                ) {
                    CardFace(card, cardWidth = cardWidth, cardHeight = cardHeight)
                }
            }
        }
    }
}
