package com.vayunmathur.games.solitaire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.ui.FreeCellBoard
import com.vayunmathur.games.solitaire.ui.GameActionBar
import com.vayunmathur.games.solitaire.ui.KlondikeBoard
import com.vayunmathur.games.solitaire.ui.SpiderBoard
import com.vayunmathur.games.solitaire.ui.WinOverlay
import com.vayunmathur.games.solitaire.util.AppBackupAgent
import com.vayunmathur.games.solitaire.util.SolitaireViewModel
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val viewModel: SolitaireViewModel = viewModel()
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    @Serializable
    data class Game(val mode: GameMode) : Route

    @Serializable
    data object GameCenter : Route
}

@Composable
fun Navigation(viewModel: SolitaireViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()

    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> {
                HomeScreen(backStack, viewModel)
            }
            entry<Route.Game> {
                GameScreen(backStack, viewModel, it.mode)
            }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    backupAgent = AppBackupAgent(),
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() }
                )
            }
        }

        newAchievement?.let {
            AchievementNotification(it) {
                viewModel.dismissAchievementNotification()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(backStack: NavBackStack<Route>, viewModel: SolitaireViewModel) {
    var showModeDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            actions = {
                IconButton(onClick = { backStack.add(Route.GameCenter) }) {
                    Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
                }
                com.vayunmathur.library.ui.BackupButtons(prefNames = listOf("solitaire_stats"))
            }
        )
    }) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val uiState by viewModel.uiState.collectAsState()
            val hasGame = viewModel.hasActiveGame()

            if (hasGame) {
                Button(
                    onClick = { backStack.add(Route.Game(uiState.gameMode!!)) },
                    Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.continue_game))
                }
            }

            Button(
                onClick = {
                    showModeDialog = true
                },
                Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.new_game))
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameMode.entries.forEach { mode ->
                    val stats = viewModel.getStats(mode)
                    val modeName = when (mode) {
                        GameMode.KLONDIKE -> stringResource(R.string.klondike)
                        GameMode.SPIDER -> stringResource(R.string.spider)
                        GameMode.FREECELL -> stringResource(R.string.freecell)
                    }
                    Card(
                        Modifier.weight(1f),
                        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                modeName,
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "${stats.gamesWon}/${stats.gamesPlayed}",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "won",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                            if (stats.bestTimeSeconds < Int.MAX_VALUE) {
                                Text(
                                    "%02d:%02d".format(stats.bestTimeSeconds / 60, stats.bestTimeSeconds % 60),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text(stringResource(R.string.select_mode)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showModeDialog = false
                            if (viewModel.hasActiveGame()) viewModel.giveUp()
                            viewModel.selectMode(GameMode.KLONDIKE, DrawMode.DRAW_ONE)
                            backStack.add(Route.Game(GameMode.KLONDIKE))
                        },
                        Modifier.fillMaxWidth()
                    ) {
                        Text("${stringResource(R.string.klondike)} — ${stringResource(R.string.draw_one)}")
                    }
                    Button(
                        onClick = {
                            showModeDialog = false
                            if (viewModel.hasActiveGame()) viewModel.giveUp()
                            viewModel.selectMode(GameMode.KLONDIKE, DrawMode.DRAW_THREE)
                            backStack.add(Route.Game(GameMode.KLONDIKE))
                        },
                        Modifier.fillMaxWidth()
                    ) {
                        Text("${stringResource(R.string.klondike)} — ${stringResource(R.string.draw_three)}")
                    }
                    Button(
                        onClick = {
                            showModeDialog = false
                            if (viewModel.hasActiveGame()) viewModel.giveUp()
                            viewModel.selectMode(GameMode.SPIDER)
                            backStack.add(Route.Game(GameMode.SPIDER))
                        },
                        Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.spider))
                    }
                    Button(
                        onClick = {
                            showModeDialog = false
                            if (viewModel.hasActiveGame()) viewModel.giveUp()
                            viewModel.selectMode(GameMode.FREECELL)
                            backStack.add(Route.Game(GameMode.FREECELL))
                        },
                        Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.freecell))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text(stringResource(R.string.back))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(backStack: NavBackStack<Route>, viewModel: SolitaireViewModel, mode: GameMode) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(mode) {
        if (uiState.gameMode != mode) {
            viewModel.selectMode(mode)
        }
    }

    LaunchedEffect(uiState.gameMode) {
        while (true) {
            delay(1000)
            viewModel.incrementTimer()
        }
    }

    val isWon = when (mode) {
        GameMode.KLONDIKE -> uiState.klondike?.isWon == true
        GameMode.SPIDER -> uiState.spider?.isWon == true
        GameMode.FREECELL -> uiState.freeCell?.isWon == true
    }
    val moveCount = when (mode) {
        GameMode.KLONDIKE -> uiState.klondike?.moveCount ?: 0
        GameMode.SPIDER -> uiState.spider?.moveCount ?: 0
        GameMode.FREECELL -> uiState.freeCell?.moveCount ?: 0
    }
    val elapsed = when (mode) {
        GameMode.KLONDIKE -> uiState.klondike?.elapsedSeconds ?: 0
        GameMode.SPIDER -> uiState.spider?.elapsedSeconds ?: 0
        GameMode.FREECELL -> uiState.freeCell?.elapsedSeconds ?: 0
    }

    val modeName = when (mode) {
        GameMode.KLONDIKE -> stringResource(R.string.klondike)
        GameMode.SPIDER -> stringResource(R.string.spider)
        GameMode.FREECELL -> stringResource(R.string.freecell)
    }
    val minutes = elapsed / 60
    val seconds = elapsed % 60
    val timeText = "%02d:%02d".format(minutes, seconds)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(modeName) },
            actions = {
                Text(
                    timeText,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 16.dp)
                )
                Text(
                    "${stringResource(R.string.moves)}: $moveCount",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        )
    }) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameActionBar(
                    onUndo = { viewModel.undo() },
                    onGiveUp = {
                        viewModel.giveUp()
                        backStack.pop()
                    },
                    undoEnabled = uiState.history.isNotEmpty() && !isWon
                )

                when (mode) {
                    GameMode.KLONDIKE -> uiState.klondike?.let {
                        KlondikeBoard(it, viewModel, Modifier.fillMaxWidth())
                        if (!it.isWon && it.tableauPiles.none { p -> p.faceDown.isNotEmpty() }) {
                            Button(
                                onClick = { viewModel.klondikeAutoComplete() },
                                Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("Auto Complete")
                            }
                        }
                    }
                    GameMode.SPIDER -> uiState.spider?.let {
                        SpiderBoard(it, viewModel, Modifier.fillMaxWidth())
                    }
                    GameMode.FREECELL -> uiState.freeCell?.let {
                        FreeCellBoard(it, viewModel, Modifier.fillMaxWidth())
                    }
                }
            }

            if (isWon) {
                WinOverlay(
                    elapsedSeconds = elapsed,
                    moveCount = moveCount,
                    onNewGame = {
                        viewModel.selectMode(mode)
                    },
                    onBack = { backStack.pop() }
                )
            }
        }
    }
}
