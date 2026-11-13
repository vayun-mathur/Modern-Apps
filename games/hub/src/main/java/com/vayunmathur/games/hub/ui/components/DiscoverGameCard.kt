package com.vayunmathur.games.hub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.domain.KnownGame
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text

/**
 * A first-party game the hub has not heard from yet, shown in the first-run empty state:
 * its name and one action - Play when the app is already on the device (opening it registers
 * the game), Install otherwise (its store listing).
 */
@Composable
fun DiscoverGameCard(
    game: KnownGame,
    isInstalled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = game.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (isInstalled) {
                val label = stringResource(R.string.play_1, game.displayName)
                Button(onClick = onOpen, modifier = Modifier.semantics { contentDescription = label }) {
                    Text(stringResource(UiR.string.play))
                }
            } else {
                val label = stringResource(R.string.install_game, game.displayName)
                OutlinedButton(onClick = onOpen, modifier = Modifier.semantics { contentDescription = label }) {
                    Text(stringResource(R.string.install))
                }
            }
        }
    }
}
