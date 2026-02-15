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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.ui.DynamicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val viewModel: ChessViewModel = viewModel()

                var showNewGameDialog by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    StockfishEngine.start(this@MainActivity, "nn-c288c895ea92.nnue", "nn-37f18f62d772.nnue")
                }

                ChessGame(
                    viewModel = viewModel,
                    onSquareClick = viewModel::onSquareClick,
                    onPromote = viewModel::onPromote,
                    onNewGame = { showNewGameDialog = true }
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
        }
    }

    companion object {
      init {
         System.loadLibrary("stockfishjni")
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
            title = { Text("Start New Game") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Play as    ")
                        SingleChoiceSegmentedButtonRow {
                            PieceColor.entries.zip(listOf("White", "Black"))
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
                        StockfishEngine.Difficulty.entries.zip(listOf("Easy", "Medium", "Hard", "Master")).forEachIndexed { idx, (value, label) ->
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
                        Text("Start Game")
                    }
                }
            },
            confirmButton = { }
        )
    } ?:
        AlertDialog(
            onDismissRequest = { },
            title = { Text(text = "New Game") },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { onNewGame(GameMode.TwoPlayer) }) {
                        Text("2-Player Local")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        showSettings = { color, difficulty ->
                            onNewGame(GameMode.VsAI(color, difficulty))
                        }
                    }) {
                        Text("Human vs AI")
                    }
                }
            },
            confirmButton = {}
        )
}

@Composable
fun ChessGame(
    viewModel: ChessViewModel,
    onSquareClick: (Position) -> Unit,
    onPromote: (PieceType) -> Unit,
    onNewGame: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    if (uiState.board.promotionPosition != null) {
        PawnPromotionDialog(if (uiState.turn == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE, onPromote = onPromote)
    }

    Scaffold { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
            Arrangement.Center,
            Alignment.CenterHorizontally
        ) {
            CapturedPiecesRow(uiState.board.capturedByBlack)
            Spacer(modifier = Modifier.height(16.dp))
            MovesList(moves = uiState.board.moves, turn = uiState.turn)
            ChessBoard(
                viewModel = viewModel,
                onSquareClick = onSquareClick
            )
            Spacer(modifier = Modifier.height(16.dp))
            CapturedPiecesRow(uiState.board.capturedByWhite)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNewGame) {
                Text("New Game")
            }

            uiState.gameStatus?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
                    Text("White", fontWeight = if (turn == PieceColor.WHITE) FontWeight.Bold else FontWeight.Normal)
                    VerticalDivider(color = MaterialTheme.colorScheme.primary)
                    Text("Black", fontWeight = if (turn == PieceColor.BLACK) FontWeight.Bold else FontWeight.Normal)
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
        title = { Text(text = "Promote Pawn") },
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

@Composable
fun ChessBoard(
    viewModel: ChessViewModel,
    onSquareClick: (Position) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val board = uiState.board
    val selectedPiece = uiState.selectedPiece
    val turn = uiState.turn
    val isFlipped = uiState.isBoardFlipped
    val isKingInCheck = board.isKingInCheck(turn)
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
                    val color = if ((i + j) % 2 == 0) Color(0xFFBBBBBB) else Color.Gray

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(color)
                            .clickable {
                                onSquareClick(Position(i, j))
                            }
                            .then(
                                if (isSelected)
                                    Modifier.border(2.dp, Color.Yellow)
                                else if (isKingInCheckSquare)
                                    Modifier.border(2.dp, Color.Red)
                                else Modifier
                            )
                    ) {
                        piece?.let {
                            ChessPiece(it, isFlipped = isFlipped)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChessPiece(piece: Piece, size: Dp? = null, isFlipped: Boolean = false) {
    Image(
        painterResource(id = piece.type.resID),
        "${piece.color} ${piece.type}",
        (if (size != null) Modifier.size(size) else Modifier.fillMaxSize())
            .graphicsLayer { if (isFlipped) rotationZ = 180f },
        colorFilter = ColorFilter.tint(if (piece.color == PieceColor.WHITE) Color.White else Color.Black)
    )
}