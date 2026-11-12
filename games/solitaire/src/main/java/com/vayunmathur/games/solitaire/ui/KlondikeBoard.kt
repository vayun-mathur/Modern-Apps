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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.R
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
                    // Empty stock: show the passes still to come, like Pyramid's ↻ (#911).
                    val passesLeft = klondikePassesLeft(state)
                    val description = when (passesLeft) {
                        null -> ""
                        UNLIMITED_PASSES -> stringResource(R.string.passes_unlimited)
                        else -> pluralStringResource(R.plurals.stock_passes_left, passesLeft, passesLeft)
                    }
                    EmptySlot(
                        modifier = Modifier
                            .clickable(enabled = passesLeft != null) { actions.drawFromStock() }
                            .semantics { if (description.isNotEmpty()) contentDescription = description },
                        label = klondikeStockLabel(passesLeft),
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
                        // Draw-one shows only the top, so the card beneath is drawn under it:
                        // the top is hidden while it is carried or flies away.
                        if (visibleCount == 1 && state.waste.size > 1) {
                            CardFace(state.waste[state.waste.size - 2], cardWidth = cardWidth, cardHeight = cardHeight)
                        }
                        visibleCards.forEachIndexed { index, card ->
                            val isTop = index == visibleCards.lastIndex
                            if (isTop) {
                                DraggableCard(
                                    card = card,
                                    sourceId = "waste",
                                    pileId = "waste",
                                    index = state.waste.lastIndex,
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

        }
    }
}

/** [klondikePassesLeft] value for a game with unlimited passes. */
internal const val UNLIMITED_PASSES = Int.MAX_VALUE

/**
 * Passes through the stock still to come once the current one ends: null when the
 * empty stock cannot be recycled (no redeals left, or nothing in the waste),
 * [UNLIMITED_PASSES] for unlimited games, else the count.
 */
internal fun klondikePassesLeft(state: KlondikeState): Int? = when {
    state.waste.isEmpty() || state.redealsRemaining <= 0 -> null
    state.redealsRemaining == Int.MAX_VALUE -> UNLIMITED_PASSES
    else -> state.redealsRemaining
}

/** Empty-stock label: blank when it cannot recycle, ↻ when unlimited, else "↻ n". */
internal fun klondikeStockLabel(passesLeft: Int?): String = when (passesLeft) {
    null -> ""
    UNLIMITED_PASSES -> "↻"
    else -> "↻ $passesLeft"
}
