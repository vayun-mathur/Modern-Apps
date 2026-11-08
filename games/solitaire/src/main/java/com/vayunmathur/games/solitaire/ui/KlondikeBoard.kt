package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.platform.SolitaireActions

@Composable
fun KlondikeBoard(state: KlondikeState, actions: SolitaireActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val cardWidth = (maxWidth - 48.dp) / 7
        val cardHeight = cardWidth * 1.5f

        Box {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Stock
                if (state.stock.isNotEmpty()) {
                    CardBack(
                        modifier = Modifier.clickable { actions.drawFromStock() },
                        cardWidth = cardWidth,
                        cardHeight = cardHeight
                    )
                } else {
                    EmptySlot(
                        modifier = Modifier.clickable { actions.drawFromStock() },
                        cardWidth = cardWidth,
                        cardHeight = cardHeight
                    )
                }

                // Waste
                if (state.waste.isNotEmpty()) {
                    val visibleCount = if (state.drawMode == DrawMode.DRAW_THREE) minOf(3, state.waste.size) else 1
                    val visibleCards = state.waste.takeLast(visibleCount)
                    val fanOffset = cardWidth * 0.3f
                    Box(modifier = Modifier.width(cardWidth + fanOffset * (visibleCount - 1))) {
                        visibleCards.forEachIndexed { index, card ->
                            val isTop = index == visibleCards.lastIndex
                            if (isTop) {
                                DraggableCard(
                                    card = card,
                                    sourceId = "waste",
                                    actions = actions,
                                    modifier = Modifier.offset(x = fanOffset * index),
                                ) {
                                    CardFace(card, cardWidth = cardWidth, cardHeight = cardHeight)
                                }
                            } else {
                                CardFace(
                                    card,
                                    modifier = Modifier.offset(x = fanOffset * index),
                                    cardWidth = cardWidth,
                                    cardHeight = cardHeight
                                )
                            }
                        }
                    }
                } else {
                    EmptySlot(cardWidth = cardWidth, cardHeight = cardHeight)
                }

                Spacer(Modifier.width(cardWidth))

                // Foundations
                for (i in 0 until 4) {
                    FoundationSlot(state.foundations[i], i, actions, cardWidth = cardWidth, cardHeight = cardHeight)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (i in 0 until 7) {
                    TableauColumn(
                        pile = state.tableauPiles[i],
                        columnIndex = i,
                        actions = actions,
                        modifier = Modifier.weight(1f),
                        cardWidth = cardWidth,
                        cardHeight = cardHeight
                    )
                }
            }
        }

        DragOverlay(actions, cardWidth, cardHeight)
        CardFlightOverlay(actions, cardWidth, cardHeight)
        }
    }
}

