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
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.data.SpiderState
import com.vayunmathur.games.solitaire.platform.SolitaireActions
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.solitaire.R

@Composable
fun SpiderBoard(state: SpiderState, actions: SolitaireActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val cardWidth = (maxWidth - 54.dp) / 10
        val cardHeight = cardWidth * 1.4f

        Box {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.completed_8, state.completedSuits),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (state.stockGroups.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(state.stockGroups.size) {
                            CardBack(
                                modifier = Modifier.clickable { actions.dealSpiderStock() },
                                cardWidth = cardWidth,
                                cardHeight = cardHeight
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (i in 0 until 10) {
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

