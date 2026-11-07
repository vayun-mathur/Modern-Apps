package com.vayunmathur.games.chess

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.vayunmathur.games.chess.data.Move
import com.vayunmathur.games.chess.data.Piece
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessAchievementsManager
import com.vayunmathur.games.chess.util.AppBackupAgent
import com.vayunmathur.games.chess.util.ChessActions
import com.vayunmathur.games.chess.util.ChessUiState
import com.vayunmathur.games.chess.util.ChessViewModel
import com.vayunmathur.games.chess.util.Difficulty
import com.vayunmathur.games.chess.util.GameMode
import com.vayunmathur.games.chess.util.GameResult
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.VerticalDivider
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
private const val FLIPPED_ROTATION = 180f
private const val CAPTURE_HINT_FRACTION = 0.92f
private const val EMPTY_HINT_FRACTION = 0.30f

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
    // Drag only while the human side is actually to move: never when the
    // game is over or while the AI is thinking.
    val dragEnabled = state.gameResult == null && when (val mode = state.gameMode) {
        GameMode.TwoPlayer -> true
        is GameMode.VsAI -> state.turn == mode.playerColor
    }
    BoardGrid(
        board = state.board,
        selectedPiece = state.selectedPiece,
        isFlipped = state.isBoardFlipped,
        turn = state.turn,
        onSquareClick = actions::onSquareClick,
        dragEnabled = dragEnabled,
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
fun MovesList(moves: List<Move>, turn: PieceColor) {
    Box(Modifier.height(100.dp)) {
        LazyColumn(
            Modifier
                .fillMaxHeight()
                .border(2.dp, Color.Gray)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    val whiteBold = turn == PieceColor.WHITE
                    val blackBold = turn == PieceColor.BLACK
                    Text(
                        stringResource(R.string.color_white),
                        fontWeight = if (whiteBold) FontWeight.Bold else FontWeight.Normal
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.color_black),
                        fontWeight = if (blackBold) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            items(moves.chunked(2)) { move ->
                Row(Modifier.fillMaxWidth()) {
                    Text(move[0].toString(), Modifier.weight(1f), textAlign = TextAlign.Center)
                    if (move.size == 2) {
                        VerticalDivider(color = MaterialTheme.colorScheme.primary)
                        Text(move[1].toString(), Modifier.weight(1f), textAlign = TextAlign.Center)
                    } else {
                        VerticalDivider()
                        Text("", Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
fun PawnPromotionDialog(color: PieceColor, onPromote: (PieceType) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(text = stringResource(R.string.promote_pawn)) },
        text = {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                for (pieceType in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                    Box(Modifier.clickable { onPromote(pieceType) }) {
                        ChessPiece(Piece(pieceType, color), 64.dp)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun CapturedPiecesRow(pieces: List<Piece>) {
    Box(
        Modifier
            .height(48.dp)
            .padding(8.dp), Alignment.Center) {
        if (pieces.isNotEmpty()) {
            Card {
                Row(
                    Modifier.padding(4.dp),
                ) {
                    pieces.forEach { ChessPiece(it, 32.dp) }
                }
            }
        }
    }
}

// File-scope so the chess board doesn't allocate 32 Color instances per recomposition.
private val lightSquareColor = Color(0xFFBBBBBB)
private val darkSquareColor = Color.Gray
private val lastMoveColor = Color(0xFF4CAF50)
private val moveHintColor = Color(0x66000000)
private const val BOARD_SQUARES = 8

private data class SquareHighlight(
    val selected: Boolean,
    val kingInCheck: Boolean,
    val lastMove: Boolean,
)

private fun squareHighlight(
    board: com.vayunmathur.games.chess.data.Board,
    selectedPiece: Position?,
    row: Int,
    col: Int,
    turn: PieceColor,
    lastMove: com.vayunmathur.games.chess.data.Move?,
): SquareHighlight {
    val piece = board.pieces[row][col]
    return SquareHighlight(
        selected = selectedPiece?.let { it.row == row && it.col == col } ?: false,
        kingInCheck = board.isKingInCheck(turn) &&
            piece?.type == PieceType.KING && piece.color == turn,
        lastMove = lastMove?.let {
            (it.start.row == row && it.start.col == col) ||
                (it.end.row == row && it.end.col == col)
        } ?: false,
    )
}

private fun squareBaseColor(row: Int, col: Int): Color =
    if ((row + col) % 2 == 0) lightSquareColor else darkSquareColor

private fun highlightModifier(highlight: SquareHighlight): Modifier {
    return when {
        highlight.selected -> Modifier.border(2.dp, Color.Yellow)
        highlight.kingInCheck -> Modifier.border(2.dp, Color.Red)
        highlight.lastMove -> Modifier.border(3.dp, lastMoveColor)
        else -> Modifier
    }
}

private fun legalDestinations(
    board: com.vayunmathur.games.chess.data.Board,
    selectedPiece: Position?,
): Set<Position> {
    val sel = selectedPiece ?: return emptySet()
    return buildSet {
        for (i in 0 until BOARD_SQUARES) {
            for (j in 0 until BOARD_SQUARES) {
                if (board.isValidMove(sel, Position(i, j))) add(Position(i, j))
            }
        }
    }
}

/**
 * The shared 8x8 board: tap-to-select / tap-to-move, plus optional
 * drag-to-move ([dragEnabled]).
 *
 * Dragging is layered on the exact tap path so every mode keeps its own
 * semantics: a drag starts by "tapping" the piece square (the owning
 * ViewModel selects it, which also lights the legal destinations exactly
 * as a tap does) and a drop on a legal square "taps" that square, so
 * validation, puzzle/learn failure rules and the promotion picker all run
 * unchanged. Dropping anywhere else — or cancelling — animates the piece
 * back to its square and undoes the drag's own selection; a piece that
 * was already tapped-selected stays selected. Only pieces of the side to
 * move ([turn]) can be picked up, and callers gate [dragEnabled] on
 * their input state (game in progress / AI thinking, puzzle solving,
 * lesson playing). The default keeps the online board tap-only.
 *
 * [dragMoveLegal] decides which squares count as legal drops while
 * dragging (and are highlighted). It defaults to [Board.isValidMove];
 * Learn passes its pseudo-legal variant for kingless/illegal-move
 * lessons, mirroring LearnViewModel.
 */
@Composable
fun BoardGrid(
    board: com.vayunmathur.games.chess.data.Board,
    selectedPiece: Position?,
    isFlipped: Boolean,
    turn: PieceColor,
    onSquareClick: (Position) -> Unit,
    showLastMove: Boolean = false,
    dragEnabled: Boolean = false,
    dragMoveLegal: ((Position, Position) -> Boolean)? = null,
) {
    // The squares the selected piece can legally move to (for move-hint dots).
    val destinations = remember(board, selectedPiece) {
        legalDestinations(board, selectedPiece)
    }
    // The puzzle board seeds a synthetic zero-length lastMove to encode turn; skip it.
    val lastMove = board.lastMove?.takeIf { showLastMove && it.start != it.end }

    val click by rememberUpdatedState(onSquareClick)
    val liveBoard by rememberUpdatedState(board)
    val liveTurn by rememberUpdatedState(turn)
    val liveFlipped by rememberUpdatedState(isFlipped)
    val liveSelected by rememberUpdatedState(selectedPiece)
    val legal by rememberUpdatedState(dragMoveLegal)

    var boardSizePx by remember { mutableIntStateOf(0) }
    var dragFrom by remember { mutableStateOf<Position?>(null) }
    var dragPiece by remember { mutableStateOf<Piece?>(null) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }
    var dragAutoSelected by remember { mutableStateOf(false) }
    var settleFrom by remember { mutableStateOf<Offset?>(null) }
    var settling by remember { mutableStateOf(false) }
    // Shared motion helper (library.ui), never a raw compose-animation call:
    // 1f while the finger is down, tweened to 0f over Motion.DropMillis on
    // a snap-back, easing the overlay from the release point home.
    val settleProgress = animatedFloat(if (settling) 0f else 1f, Motion.drop())

    LaunchedEffect(settling) {
        if (settling) {
            delay(Motion.DropMillis.toLong())
            settling = false
            settleFrom = null
            dragFrom = null
            dragPiece = null
        }
    }

    fun positionAt(point: Offset): Position? {
        if (boardSizePx <= 0) return null
        val cell = boardSizePx / BOARD_SQUARES.toFloat()
        val visualCol = (point.x / cell).toInt()
        val visualRow = (point.y / cell).toInt()
        if (visualCol !in 0 until BOARD_SQUARES || visualRow !in 0 until BOARD_SQUARES) {
            return null
        }
        return if (liveFlipped) {
            Position(BOARD_SQUARES - 1 - visualRow, BOARD_SQUARES - 1 - visualCol)
        } else {
            Position(visualRow, visualCol)
        }
    }

    fun originCenter(pos: Position): Offset {
        val cell = boardSizePx / BOARD_SQUARES.toFloat()
        val visualRow = if (isFlipped) BOARD_SQUARES - 1 - pos.row else pos.row
        val visualCol = if (isFlipped) BOARD_SQUARES - 1 - pos.col else pos.col
        return Offset((visualCol + 0.5f) * cell, (visualRow + 0.5f) * cell)
    }

    fun finishDrag(commit: Boolean) {
        val from = dragFrom ?: return
        val to = if (commit) positionAt(dragPoint) else null
        val isLegal = to != null && to != from &&
            (legal?.invoke(from, to) ?: liveBoard.isValidMove(from, to))
        if (isLegal) {
            // Commit through the tap path; the ViewModel validates again
            // and owns promotion/failure handling.
            settling = false
            settleFrom = null
            dragFrom = null
            dragPiece = null
            click(to)
        } else {
            // Snap back: undo only the selection this drag created.
            if (dragAutoSelected) click(from)
            settleFrom = dragPoint
            settling = true
        }
        dragAutoSelected = false
    }

    val dragDestinations = remember(board, dragFrom, dragMoveLegal) {
        val from = dragFrom ?: return@remember emptySet()
        buildSet {
            for (i in 0 until BOARD_SQUARES) {
                for (j in 0 until BOARD_SQUARES) {
                    val end = Position(i, j)
                    if (dragMoveLegal?.invoke(from, end) ?: board.isValidMove(from, end)) {
                        add(end)
                    }
                }
            }
        }
    }
    val shownDestinations = if (dragFrom != null && !settling) dragDestinations else destinations

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onSizeChanged { boardSizePx = it.width }
            .pointerInput(dragEnabled) {
                if (!dragEnabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        if (settling) return@detectDragGestures
                        val from = positionAt(offset) ?: return@detectDragGestures
                        val piece = liveBoard.pieces[from.row][from.col]
                            ?: return@detectDragGestures
                        if (piece.color != liveTurn) return@detectDragGestures
                        dragFrom = from
                        dragPiece = piece
                        dragPoint = offset
                        dragAutoSelected = liveSelected != from
                        if (dragAutoSelected) click(from)
                    },
                    onDrag = { change, _ ->
                        if (dragFrom != null && !settling) {
                            change.consume()
                            dragPoint = change.position
                        }
                    },
                    onDragEnd = { finishDrag(commit = true) },
                    onDragCancel = { finishDrag(commit = false) },
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { if (isFlipped) rotationZ = FLIPPED_ROTATION }
        ) {
            for (i in board.pieces.indices) {
                BoardRow(
                    board = board,
                    row = i,
                    selectedPiece = selectedPiece,
                    isFlipped = isFlipped,
                    turn = turn,
                    lastMove = lastMove,
                    destinations = shownDestinations,
                    hiddenPiece = dragFrom,
                    onSquareClick = onSquareClick
                )
            }
        }
        // The lifted piece, drawn in unrotated board space above the grid.
        val from = dragFrom
        val piece = dragPiece
        if (from != null && piece != null && boardSizePx > 0) {
            val cellPx = boardSizePx / BOARD_SQUARES.toFloat()
            val center = if (settling) {
                val startPoint = settleFrom ?: dragPoint
                val home = originCenter(from)
                Offset(
                    home.x + (startPoint.x - home.x) * settleProgress,
                    home.y + (startPoint.y - home.y) * settleProgress,
                )
            } else {
                dragPoint
            }
            val cellDp = with(LocalDensity.current) { cellPx.toDp() }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (center.x - cellPx / 2f).roundToInt(),
                            (center.y - cellPx / 2f).roundToInt(),
                        )
                    }
                    .size(cellDp)
                    .graphicsLayer {
                        scaleX = 1.08f
                        scaleY = 1.08f
                        alpha = 0.95f
                    }
            ) {
                ChessPiece(piece)
            }
        }
    }
}

@Composable
private fun ColumnScope.BoardRow(
    board: com.vayunmathur.games.chess.data.Board,
    row: Int,
    selectedPiece: Position?,
    isFlipped: Boolean,
    turn: PieceColor,
    lastMove: com.vayunmathur.games.chess.data.Move?,
    destinations: Set<Position>,
    hiddenPiece: Position?,
    onSquareClick: (Position) -> Unit,
) {
    Row(modifier = Modifier.weight(1f)) {
        for (j in board.pieces[row].indices) {
            val piece = board.pieces[row][j]
            val highlight = squareHighlight(board, selectedPiece, row, j, turn, lastMove)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .background(squareBaseColor(row, j))
                    .clickable {
                        onSquareClick(Position(row, j))
                    }
                    .then(highlightModifier(highlight))
            ) {
                // The dragged piece is drawn by BoardGrid's overlay instead.
                if (Position(row, j) != hiddenPiece) {
                    piece?.let {
                        ChessPiece(it, isFlipped = isFlipped)
                    }
                }
                if (Position(row, j) in destinations) {
                    MoveHintDot(piece != null)
                }
            }
        }
    }
}

@Composable
private fun MoveHintDot(occupied: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (occupied) {
            // Capturable square: a ring around the piece.
            Box(
                Modifier
                    .fillMaxSize(CAPTURE_HINT_FRACTION)
                    .border(3.dp, moveHintColor, CircleShape)
            )
        } else {
            // Empty square: a centered dot.
            Box(
                Modifier
                    .fillMaxSize(EMPTY_HINT_FRACTION)
                    .clip(CircleShape)
                    .background(moveHintColor)
            )
        }
    }
}

@Composable
fun ChessPiece(piece: Piece, size: Dp? = null, isFlipped: Boolean = false) {
    val description = stringResource(pieceDescriptionRes(piece))
    Image(
        painterResource(id = piece.type.resID),
        description,
        (if (size != null) Modifier.size(size) else Modifier.fillMaxSize())
            .graphicsLayer { if (isFlipped) rotationZ = FLIPPED_ROTATION },
        colorFilter = ColorFilter.tint(if (piece.color == PieceColor.WHITE) Color.White else Color.Black)
    )
}

private fun pieceDescriptionRes(piece: Piece): Int {
    return if (piece.color == PieceColor.WHITE) whitePieceRes(piece.type) else blackPieceRes(piece.type)
}

private fun whitePieceRes(type: PieceType): Int = when (type) {
    PieceType.KING -> R.string.piece_white_king
    PieceType.QUEEN -> R.string.piece_white_queen
    PieceType.ROOK -> R.string.piece_white_rook
    PieceType.BISHOP -> R.string.piece_white_bishop
    PieceType.KNIGHT -> R.string.piece_white_knight
    PieceType.PAWN -> R.string.piece_white_pawn
}

private fun blackPieceRes(type: PieceType): Int = when (type) {
    PieceType.KING -> R.string.piece_black_king
    PieceType.QUEEN -> R.string.piece_black_queen
    PieceType.ROOK -> R.string.piece_black_rook
    PieceType.BISHOP -> R.string.piece_black_bishop
    PieceType.KNIGHT -> R.string.piece_black_knight
    PieceType.PAWN -> R.string.piece_black_pawn
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
