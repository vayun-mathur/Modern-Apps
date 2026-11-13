package com.vayunmathur.games.chess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.util.ChessAchievementsManager
import com.vayunmathur.games.chess.util.ChessActions
import com.vayunmathur.games.chess.util.ChessUiState
import com.vayunmathur.games.chess.util.ChessViewModel
import com.vayunmathur.games.chess.util.Difficulty
import com.vayunmathur.games.chess.util.GameMode
import com.vayunmathur.games.chess.util.GameResult
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Board cap + moves/history panel width for the expanded (>=840dp) game layout. */
internal val ChessBoardMaxWidth = 560.dp
internal val ChessSidePanelWidth = 300.dp

private const val NEW_GAME_DIALOG_WIDTH_FRACTION = 0.9f
private const val DIFFICULTY_OPTION_COUNT = 4
private const val COLOR_OPTION_COUNT = 2
private const val FAST_WIN_MAX_MOVES = 40

/**
 * The new-game picker.
 *
 * [aiAvailable] defaults true so the store-listing preview renders the full dialog. On a real
 * device it comes from [ChessViewModel.aiAvailable], and the AI button is hidden rather than
 * disabled when the model cannot run: a mode that can never move is not worth explaining.
 */
@Composable
fun NewGameDialog(onNewGame: (GameMode) -> Unit, onDismiss: () -> Unit = {}, aiAvailable: Boolean = true) {
    var showSettings by remember { mutableStateOf<((PieceColor, Difficulty) -> Unit)?>(null) }

    showSettings?.let { startGame ->
        AiSettingsDialog(onStart = startGame, onDismiss = { showSettings = null })
    } ?: NewGameChoiceDialog(
        onNewGame = onNewGame,
        onDismiss = onDismiss,
        aiAvailable = aiAvailable,
        onShowSettings = { showSettings = it }
    )
}

@Composable
private fun AiSettingsDialog(
    onStart: (PieceColor, Difficulty) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedColor by remember { mutableStateOf(PieceColor.WHITE) }
    var selectedDifficulty by remember { mutableStateOf(Difficulty.INTERMEDIATE) }
    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        modifier = Modifier.fillMaxWidth(NEW_GAME_DIALOG_WIDTH_FRACTION),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_new_game)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.play_as))
                    ColorPicker(selectedColor = selectedColor, onSelect = { selectedColor = it })
                }
                Spacer(Modifier.height(16.dp))
                DifficultyPicker(selectedDifficulty = selectedDifficulty, onSelect = { selectedDifficulty = it })
                Spacer(Modifier.height(16.dp))
                Button({
                    onStart(selectedColor, selectedDifficulty)
                }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.start_game))
                }
            }
        },
        confirmButton = { }
    )
}

@Composable
private fun ColorPicker(
    selectedColor: PieceColor,
    onSelect: (PieceColor) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        val colorLabels = listOf(
            stringResource(R.string.color_white),
            stringResource(R.string.color_black)
        )
        PieceColor.entries.zip(colorLabels)
            .forEachIndexed { idx, (value, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(idx, COLOR_OPTION_COUNT),
                    onClick = { onSelect(value) },
                    selected = selectedColor == value,
                    label = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
    }
}

@Composable
private fun DifficultyPicker(
    selectedDifficulty: Difficulty,
    onSelect: (Difficulty) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        val difficultyLabels = listOf(
            stringResource(UiR.string.easy),
            stringResource(UiR.string.medium),
            stringResource(UiR.string.hard),
            stringResource(R.string.difficulty_master)
        )
        Difficulty.entries.zip(difficultyLabels)
            .forEachIndexed { idx, (value, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(idx, DIFFICULTY_OPTION_COUNT),
                    onClick = { onSelect(value) },
                    selected = selectedDifficulty == value,
                    label = {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                )
            }
    }
}

@Composable
private fun NewGameChoiceDialog(
    onNewGame: (GameMode) -> Unit,
    onDismiss: () -> Unit,
    aiAvailable: Boolean,
    onShowSettings: (((PieceColor, Difficulty) -> Unit)?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(UiR.string.new_game)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = { onNewGame(GameMode.TwoPlayer) }) {
                    Text(stringResource(R.string.two_player_local))
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (aiAvailable) {
                    Button(onClick = {
                        onShowSettings { color, difficulty ->
                            onNewGame(GameMode.VsAI(color, difficulty))
                        }
                    }) {
                        Text(stringResource(R.string.human_vs_ai))
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/** Binds [ChessViewModel] to the stateless [ChessGameScreen] and drives the achievements. */
@Composable
fun ChessGame(
    viewModel: ChessViewModel,
    onNewGame: () -> Unit,
    onOpenGameCenter: () -> Unit,
    achievementsManager: AchievementsManager
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    MoveAchievements(state = uiState, achievementsManager = achievementsManager)
    WinAchievements(state = uiState, viewModel = viewModel, achievementsManager = achievementsManager)

    ChessGameScreen(
        state = uiState,
        actions = viewModel,
        onNewGame = onNewGame,
        onOpenGameCenter = onOpenGameCenter
    )
}

@Composable
private fun MoveAchievements(
    state: ChessUiState,
    achievementsManager: AchievementsManager,
) {
    LaunchedEffect(state.board.lastMove) {
        val lastMove = state.board.lastMove ?: return@LaunchedEffect
        // The engine's replies land in the same board state as the human's moves, so credit the
        // move only when the side that made it is the one the player is sitting on.
        val playedByPlayer = when (val mode = state.gameMode) {
            is GameMode.VsAI -> lastMove.piece.color == mode.playerColor
            GameMode.TwoPlayer -> true
        }
        if (!playedByPlayer) return@LaunchedEffect
        if (lastMove.isCastling) {
            achievementsManager.onAchievementUnlocked("castled")
        }
        if (lastMove.promotedTo != null) {
            achievementsManager.onAchievementUnlocked("promoted")
        }
    }
}

@Composable
private fun WinAchievements(
    state: ChessUiState,
    viewModel: ChessViewModel,
    achievementsManager: AchievementsManager,
) {
    val context = LocalContext.current
    LaunchedEffect(state.gameResult) {
        // Drive achievements off the typed game result, not the localized status text, so they
        // work in every locale. Only a checkmate counts as a "win"; draws unlock nothing here.
        val result = state.gameResult as? GameResult.Checkmate ?: return@LaunchedEffect
        val mode = state.gameMode
        val playerWins = when (mode) {
            is GameMode.VsAI -> result.winner == mode.playerColor
            GameMode.TwoPlayer -> true // local play: the side that delivered mate is "the player"
        }

        if (playerWins) {
            recordWin(state, mode, viewModel, achievementsManager, context)
        }
    }
}

private suspend fun recordWin(
    state: ChessUiState,
    mode: GameMode,
    viewModel: ChessViewModel,
    achievementsManager: AchievementsManager,
    context: android.content.Context,
) {
    achievementsManager.onAchievementUnlocked("first_mate")

    if (state.board.moves.size <= FAST_WIN_MAX_MOVES) {
        achievementsManager.onAchievementUnlocked("won_fast")
    }

    if (mode is GameMode.VsAI && mode.difficulty >= Difficulty.ADVANCED) {
        achievementsManager.onAchievementUnlocked("win_vs_ai_hard")
    }

    // Only tick the persistent win counter once per game. This effect re-runs when the
    // Game screen re-enters composition (e.g. returning from the achievements screen), and
    // the outcome is still a win, so without this guard the count would climb each time.
    if (viewModel.claimWinScoring()) {
        val ds = DataStoreUtils.getInstance(context)
        val currentWins = (ds.getLong("chess_wins_count") ?: 0L) + 1
        ds.setLong("chess_wins_count", currentWins)
        achievementsManager.onProgressUpdated("win_10", currentWins.toInt())
        achievementsManager.onProgressUpdated("win_50", currentWins.toInt())
    }
}

/**
 * The play screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 * Nothing here touches the model; the engine is only ever reached through the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessGameScreen(
    state: ChessUiState,
    actions: ChessActions,
    onNewGame: () -> Unit,
    onOpenGameCenter: () -> Unit
) {
    if (state.board.promotionPosition != null) {
        PawnPromotionDialog(state.turn, onPromote = actions::onPromote)
    }

    // No title: the tab is already identified by the bottom bar, so the app bar exists only to
    // hold the achievements button.
    AppScaffold(
        title = {},
        actions = {
            IconButton(onClick = onOpenGameCenter) {
                Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Read in this scope: BoxWithConstraintsScope members are not reachable by implicit
            // receiver from inside the Row/Column lambdas below.
            val fullSide = minOf(maxWidth, maxHeight)
            // Expanded windows (>=840dp) get the side-panel treatment gated on the width
            // class, not orientation: the board is capped and centred with the
            // moves/history panel beside it, and the board itself never splits.
            // Smaller windows keep the orientation-based layout below.
            if (isExpandedWidth()) {
                ExpandedGameLayout(state = state, actions = actions, onNewGame = onNewGame)
            } else if (maxWidth > maxHeight) {
                LandscapeGameLayout(
                    state = state,
                    actions = actions,
                    onNewGame = onNewGame,
                    boardSide = fullSide
                )
            } else {
                PortraitGameLayout(state = state, actions = actions, onNewGame = onNewGame)
            }
        }
    }
}

@Composable
private fun GameBoardGrid(
    state: ChessUiState,
    actions: ChessActions,
) {
    BoardGrid(
        board = state.board,
        selectedPiece = state.selectedPiece,
        isFlipped = state.isBoardFlipped,
        turn = state.turn,
        onSquareClick = actions::onSquareClick,
        onSquareDrop = actions::onSquareDrop
    )
}

@Composable
private fun GameSidePanel(
    state: ChessUiState,
    onNewGame: () -> Unit,
) {
    CapturedPiecesRow(state.board.capturedByBlack)
    MovesList(moves = state.board.moves, turn = state.turn)
    CapturedPiecesRow(state.board.capturedByWhite)
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onNewGame) {
        Text(stringResource(UiR.string.new_game))
    }
    state.gameStatus?.let {
        Spacer(modifier = Modifier.height(16.dp))
        Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ExpandedGameLayout(
    state: ChessUiState,
    actions: ChessActions,
    onNewGame: () -> Unit,
) {
    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = ChessBoardMaxWidth).fillMaxWidth()) {
                GameBoardGrid(state = state, actions = actions)
            }
        }
        Column(
            Modifier
                .width(ChessSidePanelWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GameSidePanel(state = state, onNewGame = onNewGame)
        }
    }
}

@Composable
private fun LandscapeGameLayout(
    state: ChessUiState,
    actions: ChessActions,
    onNewGame: () -> Unit,
    boardSide: Dp,
) {
    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(boardSide)) {
            GameBoardGrid(state = state, actions = actions)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GameSidePanel(state = state, onNewGame = onNewGame)
        }
    }
}

@Composable
private fun PortraitGameLayout(
    state: ChessUiState,
    actions: ChessActions,
    onNewGame: () -> Unit,
) {
    // Everything except the board is laid out at its own height, so the New Game button
    // is always on screen; the board takes what is left over and shrinks instead. A
    // fixed full-width board plus this much chrome overflows the screen at large
    // display or font sizes, which used to push the button out of reach entirely.
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CapturedPiecesRow(state.board.capturedByBlack)
        MovesList(moves = state.board.moves, turn = state.turn)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(minOf(maxWidth, maxHeight))) {
                GameBoardGrid(state = state, actions = actions)
            }
        }
        CapturedPiecesRow(state.board.capturedByWhite)
        Button(onClick = onNewGame) {
            Text(stringResource(UiR.string.new_game))
        }

        state.gameStatus?.let {
            Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun rememberAchievementsManager(): AchievementsManager? {
    val context = LocalContext.current
    val state = produceState<AchievementsManager?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
            ChessAchievementsManager(context, json)
        }
    }
    return state.value
}
