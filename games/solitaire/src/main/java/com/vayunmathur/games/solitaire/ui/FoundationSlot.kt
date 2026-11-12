package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.platform.SolitaireActions

/**
 * A foundation pile. The top card is a [DraggableCard] so the board can hide it while it flies
 * in (or, where the variant allows, is carried back out); the card beneath — or the suit
 * watermark — is drawn under it so a hidden top never leaves a hole.
 */
@Composable
fun FoundationSlot(
    cards: List<Card>,
    index: Int,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    cardWidth: Dp = CARD_WIDTH,
    cardHeight: Dp = CARD_HEIGHT
) {
    val pileId = "foundation_$index"
    DropTarget(pileId, actions, modifier) {
        Box {
            val beneath = cards.getOrNull(cards.size - 2)
            if (beneath != null) {
                CardFace(beneath, cardWidth = cardWidth, cardHeight = cardHeight)
            } else {
                val suit = Suit.entries.getOrNull(index)
                EmptySlot(
                    label = suit?.symbol ?: "",
                    labelColor = suit?.let { LocalCardColorScheme.current.colorFor(it).copy(alpha = 0.5f) }
                        ?: Color.Gray,
                    cardWidth = cardWidth,
                    cardHeight = cardHeight
                )
            }
            cards.lastOrNull()?.let { top ->
                DraggableCard(
                    card = top,
                    sourceId = pileId,
                    pileId = pileId,
                    index = cards.lastIndex,
                    actions = actions,
                ) {
                    CardFace(top, cardWidth = cardWidth, cardHeight = cardHeight)
                }
            }
        }
    }
}
