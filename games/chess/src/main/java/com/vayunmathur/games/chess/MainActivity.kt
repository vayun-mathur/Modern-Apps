package com.vayunmathur.games.chess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.games.chess.util.ChessViewModel
import com.vayunmathur.games.chess.util.ChessUiState
import com.vayunmathur.games.chess.util.GameMode
import com.vayunmathur.games.chess.util.GameResult
import com.vayunmathur.games.chess.util.PuzzleViewModel
import com.vayunmathur.games.chess.util.PuzzleStatus
import com.vayunmathur.games.chess.util.PuzzleDifficulty
import com.vayunmathur.games.chess.util.LearnViewModel
import com.vayunmathur.games.chess.util.LearnUiState
import com.vayunmathur.games.chess.util.LearnStatus
import com.vayunmathur.games.chess.util.LearnProgress
import com.vayunmathur.games.chess.data.LearnCategory
import com.vayunmathur.games.chess.data.square
import com.vayunmathur.games.chess.util.StockfishEngine
import com.vayunmathur.games.chess.data.Piece
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.Move
import com.vayunmathur.games.chess.util.AppBackupAgent
import com.vayunmathur.games.chess.util.ChessAchievementsManager
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.AchievementNotification
import androidx.compose.runtime.produceState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.CircularProgressIndicator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DynamicTheme {
                val backStack = rememberNavBackStack<Route>(Route.Game)
                val achievementsManager = rememberAchievementsManager()
                GameHubComposeHook("chess", achievementsManager)
                if (achievementsManager == null) {
                    Box(Modifier.fillMaxSize())
                    return@DynamicTheme
                }
                val newAchievement by achievementsManager.newAchievement.collectAsState()

                LaunchedEffect(Unit) {
                    achievementsManager.checkExistingAchievements()
                }

                Box(Modifier.fillMaxSize()) {
                    val pages: List<BottomBarItem<out Route>> = listOf(
                        BottomBarItem(
                            stringResource(R.string.tab_play),
                            Route.Game,
                        ) { IconPlay() },
                        BottomBarItem(
                            stringResource(R.string.tab_puzzles),
                            Route.Puzzles,
                        ) { Icon(painterResource(R.drawable.chess_knight_fill1_24px), null) },
                        BottomBarItem(
                            stringResource(R.string.tab_learn),
                            Route.Learn,
                        ) { Icon(painterResource(R.drawable.school_24px), null) }
                    )
                    MainNavigation(
                        backStack,
                        bottomBar = {
                            val cur = backStack.last()
                            if (cur is Route.Game || cur is Route.Puzzles || cur is Route.Learn) {
                                BottomNavBar(backStack, pages, cur)
                            }
                        }
                    ) {
                        entry<Route.Game> {
                            val viewModel: ChessViewModel = viewModel()
                            var showNewGameDialog by remember { mutableStateOf(false) }

                            LaunchedEffect(Unit) {
                                StockfishEngine.start(this@MainActivity)
                            }

                            ChessGame(
                                viewModel = viewModel,
                                onSquareClick = viewModel::onSquareClick,
                                onPromote = viewModel::onPromote,
                                onNewGame = { showNewGameDialog = true },
                                onOpenGameCenter = { backStack.add(Route.GameCenter) },
                                achievementsManager = achievementsManager
                            )

                            if (showNewGameDialog) {
                                NewGameDialog(
                                    onNewGame = {
                                        viewModel.onNewGame(it)
                                        showNewGameDialog = false
                                    }
                                )
                            }
                        }
                        entry<Route.Puzzles> {
                            val puzzleViewModel: PuzzleViewModel = viewModel()
                            PuzzleScreen(puzzleViewModel)
                        }
                        entry<Route.Learn> {
                            LearnHomeScreen(
                                onOpenStage = { cat, stage ->
                                    backStack.add(Route.LearnStage(cat, stage))
                                }
                            )
                        }
                        entry<Route.LearnStage> { route ->
                            val learnViewModel: LearnViewModel = viewModel()
                            LaunchedEffect(route.categoryKey, route.stageKey) {
                                learnViewModel.loadStage(route.categoryKey, route.stageKey)
                            }
                            LearnStageScreen(
                                viewModel = learnViewModel,
                                onBack = { backStack.pop() },
                                onOpenStage = { cat, stage ->
                                    backStack.setLast(Route.LearnStage(cat, stage))
                                }
                            )
                        }
                        entry<Route.GameCenter> {
                            GameCenterScreen(
                                backupAgent = AppBackupAgent(),
                                manager = achievementsManager,
                                onBack = { backStack.pop() }
                            )
                        }
                    }

                    newAchievement?.let {
                        AchievementNotification(it) {
                            achievementsManager.dismissNotification()
                        }
                    }
                }
            }
        }
    }

}

@Composable
fun NewGameDialog(onNewGame: (GameMode) -> Unit) {
    var showSettings by remember { mutableStateOf<((PieceColor, StockfishEngine.Difficulty) -> Unit)?>(null) }

    showSettings?.let { startGame ->
        var selectedColor by remember { mutableStateOf(PieceColor.WHITE) }
        var selectedDifficulty by remember {mutableStateOf(StockfishEngine.Difficulty.INTERMEDIATE)}
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            modifier = Modifier.fillMaxWidth(0.9f),
            onDismissRequest = { showSettings = null },
            title = { Text(stringResource(R.string.start_new_game)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.play_as))
                        SingleChoiceSegmentedButtonRow {
                            PieceColor.entries.zip(listOf(stringResource(R.string.color_white), stringResource(R.string.color_black)))
                                .forEachIndexed { idx, (value, label) ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                        onClick = { selectedColor = value },
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
                    Spacer(Modifier.height(16.dp))
                    SingleChoiceSegmentedButtonRow {
                        StockfishEngine.Difficulty.entries.zip(listOf(stringResource(R.string.difficulty_easy), stringResource(R.string.difficulty_medium), stringResource(R.string.difficulty_hard), stringResource(R.string.difficulty_master))).forEachIndexed { idx, (value, label) ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(idx, 4),
                                onClick = { selectedDifficulty = value },
                                selected = selectedDifficulty == value,
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button({
                        startGame(selectedColor, selectedDifficulty)
                    }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.start_game))
                    }
                }
            },
            confirmButton = { }
        )
    } ?:
        AlertDialog(
            onDismissRequest = { },
            title = { Text(text = stringResource(R.string.new_game)) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { onNewGame(GameMode.TwoPlayer) }) {
                        Text(stringResource(R.string.two_player_local))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        showSettings = { color, difficulty ->
                            onNewGame(GameMode.VsAI(color, difficulty))
                        }
                    }) {
                        Text(stringResource(R.string.human_vs_ai))
                    }
                }
            },
            confirmButton = {}
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessGame(
    viewModel: ChessViewModel,
    onSquareClick: (Position) -> Unit,
    onPromote: (PieceType) -> Unit,
    onNewGame: () -> Unit,
    onOpenGameCenter: () -> Unit,
    achievementsManager: AchievementsManager
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.board.lastMove) {
        val lastMove = uiState.board.lastMove ?: return@LaunchedEffect
        if (lastMove.isCastling) {
            achievementsManager.onAchievementUnlocked("castled")
        }
        if (lastMove.promotedTo != null) {
            achievementsManager.onAchievementUnlocked("promoted")
        }
    }

    LaunchedEffect(uiState.gameResult) {
        // Drive achievements off the typed game result, not the localized status text, so they
        // work in every locale. Only a checkmate counts as a "win"; draws unlock nothing here.
        val result = uiState.gameResult as? GameResult.Checkmate ?: return@LaunchedEffect
        val mode = uiState.gameMode
        val playerWins = when (mode) {
            is GameMode.VsAI -> result.winner == mode.playerColor
            GameMode.TwoPlayer -> true // local play: the side that delivered mate is "the player"
        }

        if (playerWins) {
            achievementsManager.onAchievementUnlocked("first_mate")
            val ds = DataStoreUtils.getInstance(context)
            val currentWins = (ds.getLong("chess_wins_count") ?: 0L) + 1
            ds.setLong("chess_wins_count", currentWins)
            achievementsManager.onProgressUpdated("win_10", currentWins.toInt())
            achievementsManager.onProgressUpdated("win_50", currentWins.toInt())

            if (uiState.board.moves.size <= 40) {
                achievementsManager.onAchievementUnlocked("won_fast")
            }

            if (mode is GameMode.VsAI && mode.difficulty >= StockfishEngine.Difficulty.ADVANCED) {
                achievementsManager.onAchievementUnlocked("win_vs_ai_hard")
            }
        }
    }
    if (uiState.board.promotionPosition != null) {
        PawnPromotionDialog(uiState.turn, onPromote = onPromote)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenGameCenter) {
                        Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
                    }
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val boardSide = minOf(maxWidth, maxHeight)
            val boardComposable = @Composable {
                Box(Modifier.size(boardSide)) {
                    BoardGrid(
                        board = uiState.board,
                        selectedPiece = uiState.selectedPiece,
                        isFlipped = uiState.isBoardFlipped,
                        turn = uiState.turn,
                        onSquareClick = onSquareClick
                    )
                }
            }
            if (maxWidth > maxHeight) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    boardComposable()
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CapturedPiecesRow(uiState.board.capturedByBlack)
                        MovesList(moves = uiState.board.moves, turn = uiState.turn)
                        CapturedPiecesRow(uiState.board.capturedByWhite)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onNewGame) {
                            Text(stringResource(R.string.new_game))
                        }
                        uiState.gameStatus?.let {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    Arrangement.Center,
                    Alignment.CenterHorizontally
                ) {
                    CapturedPiecesRow(uiState.board.capturedByBlack)
                    Spacer(modifier = Modifier.height(16.dp))
                    MovesList(moves = uiState.board.moves, turn = uiState.turn)
                    boardComposable()
                    Spacer(modifier = Modifier.height(16.dp))
                    CapturedPiecesRow(uiState.board.capturedByWhite)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onNewGame) {
                        Text(stringResource(R.string.new_game))
                    }

                    uiState.gameStatus?.let {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
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
                    Text(stringResource(R.string.color_white), fontWeight = if (turn == PieceColor.WHITE) FontWeight.Bold else FontWeight.Normal)
                    VerticalDivider(color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.color_black), fontWeight = if (turn == PieceColor.BLACK) FontWeight.Bold else FontWeight.Normal)
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

@Composable
fun BoardGrid(
    board: com.vayunmathur.games.chess.data.Board,
    selectedPiece: Position?,
    isFlipped: Boolean,
    turn: PieceColor,
    onSquareClick: (Position) -> Unit,
    showLastMove: Boolean = false
) {
    val isKingInCheck = board.isKingInCheck(turn)
    // The squares the selected piece can legally move to (for move-hint dots).
    val destinations = remember(board, selectedPiece) {
        val sel = selectedPiece
        if (sel == null) emptySet()
        else buildSet {
            for (i in board.pieces.indices) for (j in board.pieces[i].indices) {
                if (board.isValidMove(sel, Position(i, j))) add(Position(i, j))
            }
        }
    }
    // The puzzle board seeds a synthetic zero-length lastMove to encode turn; skip it.
    val lastMove = board.lastMove?.takeIf { showLastMove && it.start != it.end }
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer { if (isFlipped) rotationZ = 180f }
    ) {
        for (i in board.pieces.indices) {
            Row(modifier = Modifier.weight(1f)) {
                for (j in board.pieces[i].indices) {
                    val piece = board.pieces[i][j]
                    val isSelected = selectedPiece?.let { it.row == i && it.col == j } ?: false
                    val isKingInCheckSquare =
                        isKingInCheck && piece?.type == PieceType.KING && piece.color == turn
                    val isLastMoveSquare = lastMove?.let {
                        (it.start.row == i && it.start.col == j) || (it.end.row == i && it.end.col == j)
                    } ?: false
                    val color = if ((i + j) % 2 == 0) lightSquareColor else darkSquareColor

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(color)
                            .clickable {
                                onSquareClick(Position(i, j))
                            }
                            .then(
                                when {
                                    isSelected -> Modifier.border(2.dp, Color.Yellow)
                                    isKingInCheckSquare -> Modifier.border(2.dp, Color.Red)
                                    isLastMoveSquare -> Modifier.border(3.dp, lastMoveColor)
                                    else -> Modifier
                                }
                            )
                    ) {
                        piece?.let {
                            ChessPiece(it, isFlipped = isFlipped)
                        }
                        if (Position(i, j) in destinations) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (piece != null) {
                                    // Capturable square: a ring around the piece.
                                    Box(
                                        Modifier
                                            .fillMaxSize(0.92f)
                                            .border(3.dp, moveHintColor, CircleShape)
                                    )
                                } else {
                                    // Empty square: a centered dot.
                                    Box(
                                        Modifier
                                            .fillMaxSize(0.30f)
                                            .clip(CircleShape)
                                            .background(moveHintColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChessPiece(piece: Piece, size: Dp? = null, isFlipped: Boolean = false) {
    val description = when(piece.color) {
        PieceColor.WHITE -> when(piece.type) {
            PieceType.KING -> stringResource(R.string.piece_white_king)
            PieceType.QUEEN -> stringResource(R.string.piece_white_queen)
            PieceType.ROOK -> stringResource(R.string.piece_white_rook)
            PieceType.BISHOP -> stringResource(R.string.piece_white_bishop)
            PieceType.KNIGHT -> stringResource(R.string.piece_white_knight)
            PieceType.PAWN -> stringResource(R.string.piece_white_pawn)
        }
        PieceColor.BLACK -> when(piece.type) {
            PieceType.KING -> stringResource(R.string.piece_black_king)
            PieceType.QUEEN -> stringResource(R.string.piece_black_queen)
            PieceType.ROOK -> stringResource(R.string.piece_black_rook)
            PieceType.BISHOP -> stringResource(R.string.piece_black_bishop)
            PieceType.KNIGHT -> stringResource(R.string.piece_black_knight)
            PieceType.PAWN -> stringResource(R.string.piece_black_pawn)
        }
    }
    Image(
        painterResource(id = piece.type.resID),
        description,
        (if (size != null) Modifier.size(size) else Modifier.fillMaxSize())
            .graphicsLayer { if (isFlipped) rotationZ = 180f },
        colorFilter = ColorFilter.tint(if (piece.color == PieceColor.WHITE) Color.White else Color.Black)
    )
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Game: Route
    @Serializable
    data object Puzzles: Route
    @Serializable
    data object Learn: Route
    @Serializable
    data class LearnStage(val categoryKey: String, val stageKey: String): Route
    @Serializable
    data object GameCenter: Route
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnHomeScreen(onOpenStage: (String, String) -> Unit) {
    val context = LocalContext.current
    val categories by produceState<List<LearnCategory>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            com.vayunmathur.games.chess.data.LearnRepository.ensureLoaded(context)
            com.vayunmathur.games.chess.data.LearnRepository.categories
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_learn)) }) }
    ) { pad ->
        val cats = categories
        if (cats == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            cats.forEach { cat ->
                item(key = "cat_${cat.key}") {
                    Text(
                        cat.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                items(cat.stages, key = { "stage_${it.key}" }) { stage ->
                    val done = LearnProgress.completedCount(context, stage)
                    Card(Modifier.fillMaxWidth().clickable { onOpenStage(cat.key, stage.key) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stage.title, fontWeight = FontWeight.Bold)
                                Text(
                                    stage.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                stringResource(R.string.learn_progress, done, stage.levels.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (done == stage.levels.size) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnStageScreen(
    viewModel: LearnViewModel,
    onBack: () -> Unit,
    onOpenStage: (String, String) -> Unit
) {
    val ui by viewModel.uiState.collectAsState()
    val stage = ui.stage
    val level = ui.level

    // Lichess shows a "Stage N: title … Let's go!" card once when the stage opens.
    var showIntro by remember(stage?.key) { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stage?.title ?: stringResource(R.string.tab_learn)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        IconBack()
                    }
                }
            )
        }
    ) { pad ->
        if (stage == null || level == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val isLast = ui.levelIndex + 1 >= stage.levels.size
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stage.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                level.goal,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            LearnBoard(ui, viewModel::onSquareClick)
            Spacer(Modifier.height(12.dp))
            LevelStepper(
                count = stage.levels.size,
                current = ui.levelIndex,
                stars = ui.stageStars,
                onSelect = { viewModel.goToLevel(it) }
            )
            Spacer(Modifier.height(12.dp))

            // Fixed-height status/action area so the board never shifts.
            Box(Modifier.height(112.dp), contentAlignment = Alignment.TopCenter) {
                when (ui.status) {
                    LearnStatus.Completed -> if (!isLast) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StarRow(ui.starsEarned)
                        Text(stringResource(R.string.learn_completed), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.nextLevel() }) {
                            Text(stringResource(R.string.learn_next))
                        }
                    }
                    LearnStatus.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.learn_failed), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.retryLevel() }) {
                            Text(stringResource(R.string.learn_retry))
                        }
                    }
                    LearnStatus.Playing -> {}
                }
            }
        }

        if (showIntro) StageIntroDialog(stage, onStart = { showIntro = false })

        // Pawn promotion picker (supports underpromotion lessons).
        if (ui.board.promotionPosition != null) {
            PawnPromotionDialog(ui.playerColor, onPromote = viewModel::onPromote)
        }

        // Stage-complete overlay after the final level (Lichess "Stage N complete").
        if (ui.status == LearnStatus.Completed && isLast) {
            val next = com.vayunmathur.games.chess.data.LearnRepository.nextStage(stage.key)
            StageCompleteDialog(
                stage = stage,
                stageStars = ui.stageStars,
                score = ui.stageScore,
                nextTitle = next?.second?.title,
                onNext = { next?.let { onOpenStage(it.first, it.second.key) } },
                onBackToMenu = onBack
            )
        }
    }
}

@Composable
fun StageCompleteDialog(
    stage: com.vayunmathur.games.chess.data.LearnStage,
    stageStars: List<Int>,
    score: Int,
    nextTitle: String?,
    onNext: () -> Unit,
    onBackToMenu: () -> Unit
) {
    val total = stageStars.sum()
    val max = stageStars.size * 3
    val rank = when {
        max > 0 && total >= max -> 3
        max > 0 && total >= max * 2 / 3 -> 2
        else -> 1
    }
    AlertDialog(
        onDismissRequest = onBackToMenu,
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                StarRow(rank)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.learn_stage_complete_n, stage.id))
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.learn_your_score, score),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(stage.complete, textAlign = TextAlign.Center)
            }
        },
        confirmButton = {
            if (nextTitle != null) {
                Button(onClick = onNext, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.learn_next_stage, nextTitle))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onBackToMenu, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.learn_back_to_menu))
            }
        }
    )
}

private fun stageIconRes(key: String): Int = when (key) {
    "rook" -> R.drawable.chess_rook_fill1_24px
    "bishop" -> R.drawable.chess_bishop_fill1_24px
    "queen" -> R.drawable.chess_queen_fill1_24px
    "king" -> R.drawable.chess_king_2_fill1_24px
    "knight" -> R.drawable.chess_knight_fill1_24px
    "pawn" -> R.drawable.chess_pawn_fill1_24px
    else -> R.drawable.school_24px
}

@Composable
fun StageIntroDialog(stage: com.vayunmathur.games.chess.data.LearnStage, onStart: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStart,
        title = {
            Text(
                stringResource(R.string.learn_stage_number, stage.id, stage.title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(stageIconRes(stage.key)), null, Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text(stage.intro, textAlign = TextAlign.Center)
            }
        },
        confirmButton = {
            Button(onClick = onStart, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.learn_lets_go))
            }
        }
    )
}

@Composable
fun LevelStepper(count: Int, current: Int, stars: List<Int>, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (i in 0 until count) {
            val done = stars.getOrElse(i) { 0 } > 0
            val isCurrent = i == current
            val bg = when {
                isCurrent -> MaterialTheme.colorScheme.primary
                done -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val fg = when {
                isCurrent -> MaterialTheme.colorScheme.onPrimary
                done -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                val starCount = stars.getOrElse(i) { 0 }
                if (done && !isCurrent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        repeat(starCount) {
                            Text("\u2605", fontSize = 9.sp, color = Color(0xFFFFC107))
                        }
                    }
                } else {
                    Text(
                        "${i + 1}",
                        color = fg,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun StarRow(stars: Int) {
    Row {
        for (i in 1..3) {
            Text(
                if (i <= stars) "\u2605" else "\u2606",
                fontSize = 28.sp,
                color = if (i <= stars) Color(0xFFFFC107) else Color.Gray
            )
        }
    }
}

private fun brushColor(brush: String): Color = when (brush) {
    "red" -> Color(0xCCE04040)
    "yellow" -> Color(0xCCE0B020)
    "blue" -> Color(0xCC4070E0)
    else -> Color(0xCC2F9E52) // green / paleGreen
}

private fun DrawScope.drawLearnArrow(from: Position, to: Position, cell: Float, color: Color) {
    val start = Offset(from.col * cell + cell / 2, from.row * cell + cell / 2)
    val end = Offset(to.col * cell + cell / 2, to.row * cell + cell / 2)
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    if (dist == 0f) return
    val ux = dx / dist
    val uy = dy / dist
    // Arrowhead spanning ~90% of a square, matching Lichess's chunky learn arrows.
    val headWidth = cell * 0.9f
    val headLen = cell * 0.8f
    val baseX = end.x - ux * headLen
    val baseY = end.y - uy * headLen
    val perpX = -uy
    val perpY = ux
    val p1 = Offset(baseX + perpX * headWidth / 2, baseY + perpY * headWidth / 2)
    val p2 = Offset(baseX - perpX * headWidth / 2, baseY - perpY * headWidth / 2)
    drawLine(color, start, Offset(baseX, baseY), strokeWidth = cell * 0.18f, cap = StrokeCap.Round)
    val path = Path().apply {
        moveTo(end.x, end.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawStar(center: Offset, outerR: Float, innerR: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outerR else innerR
        val a = -Math.PI / 2 + i * Math.PI / 5
        val x = (center.x + cos(a) * r).toFloat()
        val y = (center.y + sin(a) * r).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

@Composable
fun LearnBoard(ui: LearnUiState, onSquareClick: (Position) -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
        BoardGrid(
            board = ui.board,
            selectedPiece = ui.selectedPiece,
            isFlipped = ui.isFlipped,
            turn = ui.playerColor,
            onSquareClick = onSquareClick
        )
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer { if (ui.isFlipped) rotationZ = 180f }
        ) {
            val cell = size.width / 8f
            ui.shapes.forEach { s ->
                val color = brushColor(s.brush)
                val o = square(s.orig)
                val dest = s.dest
                if (dest != null) {
                    drawLearnArrow(o, square(dest), cell, color)
                } else {
                    drawCircle(
                        color,
                        radius = cell * 0.42f,
                        center = Offset(o.col * cell + cell / 2, o.row * cell + cell / 2),
                        style = Stroke(width = cell * 0.08f)
                    )
                }
            }
            ui.apples.forEach { a ->
                val center = Offset(a.col * cell + cell / 2, a.row * cell + cell / 2)
                drawStar(center, cell * 0.36f, cell * 0.15f, Color(0xF0FFC107))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PuzzleScreen(viewModel: PuzzleViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        if (uiState.puzzle == null) viewModel.loadRandom()
    }

    if (uiState.board.promotionPosition != null) {
        PawnPromotionDialog(uiState.playerColor, onPromote = viewModel::onPromote)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_puzzles)) }) }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            Arrangement.Center,
            Alignment.CenterHorizontally
        ) {
            SingleChoiceSegmentedButtonRow {
                val labels = listOf(
                    stringResource(R.string.puzzle_difficulty_easy),
                    stringResource(R.string.puzzle_difficulty_medium),
                    stringResource(R.string.puzzle_difficulty_hard)
                )
                PuzzleDifficulty.entries.forEachIndexed { idx, diff ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(idx, PuzzleDifficulty.entries.size),
                        onClick = { if (uiState.difficulty != diff) viewModel.loadRandom(diff) },
                        selected = uiState.difficulty == diff,
                        label = { Text(labels[idx], style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.puzzle_rating, uiState.rating),
                fontWeight = FontWeight.Bold
            )
            Text(
                if (uiState.playerColor == PieceColor.WHITE)
                    stringResource(R.string.puzzle_white_to_move)
                else stringResource(R.string.puzzle_black_to_move),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))

            BoardGrid(
                board = uiState.board,
                selectedPiece = uiState.selectedPiece,
                isFlipped = uiState.isBoardFlipped,
                turn = uiState.playerColor,
                onSquareClick = viewModel::onSquareClick,
                showLastMove = true
            )
            Spacer(Modifier.height(16.dp))

            val statusText = when (uiState.status) {
                PuzzleStatus.Loading -> stringResource(R.string.puzzle_loading)
                PuzzleStatus.Solving -> stringResource(R.string.puzzle_your_move)
                PuzzleStatus.Solved -> stringResource(R.string.puzzle_solved)
                PuzzleStatus.Failed -> stringResource(R.string.puzzle_failed)
                PuzzleStatus.ShowingSolution -> stringResource(R.string.puzzle_show_solution)
            }
            Text(
                statusText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))

            // Reserve the action-row height whether or not the Failed buttons are
            // shown, so the board and everything above it never shift.
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                if (uiState.status == PuzzleStatus.Failed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.puzzle_retry))
                        }
                        OutlinedButton(onClick = { viewModel.showSolution() }) {
                            Text(stringResource(R.string.puzzle_show_solution))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { viewModel.loadRandom() }) {
                Text(stringResource(R.string.puzzle_next))
            }
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