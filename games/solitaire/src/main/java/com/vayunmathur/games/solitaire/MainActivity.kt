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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty
import com.vayunmathur.games.solitaire.ui.FreeCellBoard
import com.vayunmathur.games.solitaire.ui.GameActionBar
import com.vayunmathur.games.solitaire.ui.KlondikeBoard
import com.vayunmathur.games.solitaire.ui.PyramidBoard
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

private val SolitaireBoardMaxWidth = 640.dp

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
fun GameMode.displayName(): String = when (this) {
    GameMode.KLONDIKE -> stringResource(R.string.klondike)
    GameMode.SPIDER -> stringResource(R.string.spider)
    GameMode.FREECELL -> stringResource(R.string.freecell)
    GameMode.PYRAMID -> stringResource(R.string.pyramid)
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
    var showGamePicker by remember { mutableStateOf(false) }
    var configMode by remember { mutableStateOf<GameMode?>(null) }

    val startGame = { mode: GameMode, config: GameConfig ->
        showGamePicker = false
        configMode = null
        if (viewModel.hasActiveGame()) viewModel.giveUp()
        viewModel.selectMode(mode, config)
        backStack.add(Route.Game(mode))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            actions = {
                IconButton(onClick = { backStack.add(Route.GameCenter) }) {
                    Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
                }
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
                    showGamePicker = true
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
                    val modeName = mode.displayName()
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

    if (showGamePicker) {
        AlertDialog(
            onDismissRequest = { showGamePicker = false },
            title = { Text(stringResource(R.string.select_mode)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameMode.entries.forEach { mode ->
                        Button(
                            onClick = {
                                // FreeCell has no options — start immediately.
                                if (mode == GameMode.FREECELL) startGame(mode, GameConfig())
                                else { showGamePicker = false; configMode = mode }
                            },
                            Modifier.fillMaxWidth()
                        ) {
                            Text(mode.displayName())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGamePicker = false }) {
                    Text(stringResource(R.string.back))
                }
            }
        )
    }

    configMode?.let { mode ->
        GameConfigDialog(
            mode = mode,
            onStart = { config -> startGame(mode, config) },
            onDismiss = { configMode = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameConfigDialog(mode: GameMode, onStart: (GameConfig) -> Unit, onDismiss: () -> Unit) {
    var drawMode by remember { mutableStateOf(DrawMode.DRAW_ONE) }
    var klondikeDifficulty by remember { mutableStateOf(KlondikeDifficulty.REGULAR) }
    var relaxed by remember { mutableStateOf(false) }
    var spiderSuits by remember { mutableStateOf(4) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(mode.displayName()) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (mode) {
                    GameMode.KLONDIKE -> {
                        SingleChoiceSegmentedButtonRow {
                            listOf(
                                DrawMode.DRAW_ONE to R.string.draw_one,
                                DrawMode.DRAW_THREE to R.string.draw_three
                            ).forEachIndexed { idx, (value, label) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                    onClick = { drawMode = value },
                                    selected = drawMode == value
                                ) { Text(stringResource(label)) }
                            }
                        }
                        SingleChoiceSegmentedButtonRow {
                            listOf(
                                KlondikeDifficulty.RELAXED to R.string.mode_relaxed,
                                KlondikeDifficulty.REGULAR to R.string.difficulty_regular,
                                KlondikeDifficulty.HARD to R.string.difficulty_hard
                            ).forEachIndexed { idx, (value, label) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(idx, 3),
                                    onClick = { klondikeDifficulty = value },
                                    selected = klondikeDifficulty == value
                                ) { Text(stringResource(label)) }
                            }
                        }
                    }
                    GameMode.SPIDER -> {
                        SingleChoiceSegmentedButtonRow {
                            listOf(
                                1 to R.string.difficulty_easy,
                                2 to R.string.difficulty_medium,
                                4 to R.string.difficulty_hard
                            ).forEachIndexed { idx, (value, label) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(idx, 3),
                                    onClick = { spiderSuits = value },
                                    selected = spiderSuits == value
                                ) { Text(stringResource(label)) }
                            }
                        }
                    }
                    GameMode.PYRAMID -> DifficultyRow(relaxed) { relaxed = it }
                    GameMode.FREECELL -> {}
                }
                Button(
                    onClick = { onStart(GameConfig(drawMode = drawMode, klondikeDifficulty = klondikeDifficulty, relaxed = relaxed, spiderSuits = spiderSuits)) },
                    Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.new_game))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DifficultyRow(relaxed: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        listOf(
            false to R.string.mode_original,
            true to R.string.mode_relaxed
        ).forEachIndexed { idx, (value, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(idx, 2),
                onClick = { onChange(value) },
                selected = relaxed == value
            ) { Text(stringResource(label)) }
        }
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

    val activeGame = when (mode) {
        GameMode.KLONDIKE -> uiState.klondike?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
        GameMode.SPIDER -> uiState.spider?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
        GameMode.FREECELL -> uiState.freeCell?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
        GameMode.PYRAMID -> uiState.pyramid?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
    }
    val isWon = activeGame?.first == true
    val moveCount = activeGame?.second ?: 0
    val elapsed = activeGame?.third ?: 0
    val modeName = mode.displayName()
    val timeText = "%02d:%02d".format(elapsed / 60, elapsed % 60)

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
                        KlondikeBoard(
                            it,
                            viewModel,
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .widthIn(max = SolitaireBoardMaxWidth)
                                .fillMaxWidth()
                        )
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
                        SpiderBoard(
                            it,
                            viewModel,
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .widthIn(max = SolitaireBoardMaxWidth)
                                .fillMaxWidth()
                        )
                    }
                    GameMode.FREECELL -> uiState.freeCell?.let {
                        FreeCellBoard(
                            it,
                            viewModel,
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .widthIn(max = SolitaireBoardMaxWidth)
                                .fillMaxWidth()
                        )
                    }
                    GameMode.PYRAMID -> uiState.pyramid?.let {
                        PyramidBoard(
                            it,
                            viewModel,
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .widthIn(max = SolitaireBoardMaxWidth)
                                .fillMaxWidth()
                        )
                    }
                }
            }

            if (isWon) {
                WinOverlay(
                    elapsedSeconds = elapsed,
                    moveCount = moveCount,
                    onNewGame = {
                        viewModel.selectMode(mode, viewModel.currentConfig())
                    },
                    onBack = { backStack.pop() }
                )
            }
        }
    }
}
