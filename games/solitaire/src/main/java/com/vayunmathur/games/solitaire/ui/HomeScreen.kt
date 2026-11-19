package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.Route
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.platform.SolitaireViewModel
import com.vayunmathur.games.solitaire.ui.dialogs.GameConfigDialog
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.GameTopBarActions
import com.vayunmathur.library.ui.game.formatDuration
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(backStack: NavBackStack<Route>, viewModel: SolitaireViewModel) {
    var showGamePicker by remember { mutableStateOf(false) }
    var configMode by remember { mutableStateOf<GameMode?>(null) }
    val startGame = { mode: GameMode, config: GameConfig ->
        showGamePicker = false
        configMode = null
        if (viewModel.hasActiveGame()) viewModel.giveUp()
        viewModel.selectMode(mode, config)
        backStack.add(Route.Game(mode))
    }
    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            GameTopBarActions(
                onOpenGameCenter = { backStack.add(Route.GameCenter) },
                onOpenSettings = { backStack.add(Route.Settings) },
            )
        },
    ) { paddingValues ->
        Column(
            Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val uiState by viewModel.uiState.collectAsState()
            val hasGame = viewModel.hasActiveGame()
            if (hasGame) {
                Button(onClick = { backStack.add(Route.Game(uiState.gameMode!!)) }, Modifier.fillMaxWidth()) { Text(stringResource(UiR.string.continue_label)) }
            }
            Button(onClick = { showGamePicker = true }, Modifier.fillMaxWidth()) { Text(stringResource(UiR.string.new_game)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameMode.entries.forEach { mode ->
                    val stats = viewModel.getStats(mode)
                    val modeName = mode.displayName()
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(modeName, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                            Text("${stats.gamesWon}/${stats.gamesPlayed}", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                            Text(stringResource(R.string.won), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                            if (stats.bestTimeSeconds < Int.MAX_VALUE) {
                                Text(formatDuration(stats.bestTimeSeconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showGamePicker) {
        AlertDialog(
            onDismissRequest = { showGamePicker = false },
            title = { Text(stringResource(R.string.select_mode)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameMode.entries.forEach { mode ->
                        Button(onClick = { if (mode == GameMode.FREECELL) startGame(mode, GameConfig()) else { showGamePicker = false; configMode = mode } }, Modifier.fillMaxWidth()) { Text(mode.displayName()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showGamePicker = false }) { Text(stringResource(UiR.string.back)) } }
        )
    }
    configMode?.let { mode ->
        GameConfigDialog(mode = mode, onStart = { config -> startGame(mode, config) }, onDismiss = { configMode = null })
    }
}
