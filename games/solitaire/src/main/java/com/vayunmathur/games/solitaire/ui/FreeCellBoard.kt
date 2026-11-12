package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.platform.SolitaireActions

@Composable
fun FreeCellBoard(state: FreeCellState, actions: SolitaireActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val cardWidth = (maxWidth - 48.dp) / 8
        val cardHeight = cardWidth * 1.4f

        Box {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (i in 0 until 4) {
                    FreeCellSlot(state.freeCells[i], i, actions, cardWidth = cardWidth, cardHeight = cardHeight)
                }
                for (i in 0 until 4) {
                    FoundationSlot(state.foundations[i], i, actions, cardWidth = cardWidth, cardHeight = cardHeight)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (i in 0 until 8) {
                    FreeCellTableauColumn(
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
        }
    }
}

