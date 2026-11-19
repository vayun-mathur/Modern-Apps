package com.vayunmathur.games.chess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.util.PuzzleActions
import com.vayunmathur.games.chess.util.PuzzleDifficulty
import com.vayunmathur.games.chess.util.PuzzleStatus
import com.vayunmathur.games.chess.util.PuzzleUiState
import com.vayunmathur.games.chess.util.PuzzleViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth

private const val PUZZLE_DIFFICULTY_COUNT = 3

/** The board's share of the width in the side-by-side layout (as in sudoku). */
private const val BOARDSIDE_WIDTH_SHARE = 0.62f

/** Binds [PuzzleViewModel] to the stateless [PuzzleBoardScreen] and deals the first puzzle. */
@Composable
fun PuzzleScreen(viewModel: PuzzleViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        if (uiState.puzzle == null) viewModel.loadRandom(uiState.difficulty)
    }

    PuzzleBoardScreen(state = uiState, actions = viewModel)
}

/** Difficulty selector, rating and side-to-move header for the puzzle screen. */
@Composable
private fun PuzzleHeader(state: PuzzleUiState, actions: PuzzleActions) {
    PuzzleDifficultySelector(state = state, actions = actions)
    Spacer(Modifier.height(12.dp))

    Text(
        stringResource(R.string.puzzle_rating, state.rating),
        fontWeight = FontWeight.Bold
    )
    Text(
        if (state.playerColor == PieceColor.WHITE) {
            stringResource(R.string.puzzle_white_to_move)
        } else {
            stringResource(R.string.puzzle_black_to_move)
        },
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * The puzzle screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable

fun PuzzleBoardScreen(state: PuzzleUiState, actions: PuzzleActions) {
    if (state.board.promotionPosition != null) {
        PawnPromotionDialog(state.playerColor, onPromote = actions::onPromote, onDismiss = actions::onCancelPromotion)
    }

    // No app bar: the tab has no title worth showing and no actions, so the content gets the space.
    // RAW SCAFFOLD EXCEPTION: bar-less game board; the shared scaffolds all impose a top app bar.
    Scaffold { innerPadding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // The same split as the main game (ChessGameScreen): expanded
            // windows and short/landscape ones put the board beside a
            // scrolling control column. The old centred non-scrolling Column
            // let the capped square board push Next off a landscape phone
            // entirely. Portrait windows keep the stacked layout.
            val sideBySide = isExpandedWidth() || isCompactHeight() || maxWidth > maxHeight
            if (sideBySide) {
                // Read in this scope: RowScope would shadow the
                // BoxWithConstraintsScope's maxWidth/maxHeight below.
                // On a landscape phone the height binds first; capped like
                // the game board so a desktop window doesn't blow the grid
                // up, and at a share of the width (sudoku's side-by-side cap)
                // so a narrow-but-short window still leaves the controls a
                // column — full width there squeezed them to zero.
                val boardSide = minOf(maxWidth * BOARDSIDE_WIDTH_SHARE, maxHeight, ChessBoardMaxWidth)
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PuzzleBoardBox(state = state, actions = actions, modifier = Modifier.size(boardSide))
                    // No fillMaxHeight and no centred arrangement: the column
                    // wraps its content and the Row's CenterVertically centres
                    // the whole block (sudoku's side column does the same). A
                    // centred arrangement inside a scroll container never
                    // settles — it re-centres on every scroll layout.
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PuzzleHeader(state = state, actions = actions)
                        Spacer(Modifier.height(16.dp))
                        PuzzleControls(state = state, actions = actions)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    Arrangement.Center,
                    Alignment.CenterHorizontally
                ) {
                    PuzzleHeader(state = state, actions = actions)
                    PuzzleBoardBox(
                        state = state,
                        actions = actions,
                        modifier = Modifier.widthIn(max = ChessBoardMaxWidth).fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    PuzzleControls(state = state, actions = actions)
                }
            }
        }
    }
}

/** The board in the box its caller sized: [BoardGrid] itself is size-agnostic. */
@Composable
private fun PuzzleBoardBox(state: PuzzleUiState, actions: PuzzleActions, modifier: Modifier = Modifier) {
    Box(modifier) {
        BoardGrid(
            board = state.board,
            selectedPiece = state.selectedPiece,
            isFlipped = state.isBoardFlipped,
            turn = state.playerColor,
            onSquareClick = actions::onSquareClick,
            onSquareDrop = actions::onSquareDrop,
            showLastMove = true
        )
    }
}

/** Status, the Failed actions and Next: the controls that must stay reachable. */
@Composable
private fun PuzzleControls(state: PuzzleUiState, actions: PuzzleActions) {
    PuzzleStatusText(status = state.status)
    Spacer(Modifier.height(16.dp))

    // Reserve the action-row height whether or not the Failed buttons are
    // shown, so the board and everything above it never shift.
    Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
        if (state.status == PuzzleStatus.Failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.retry() }) {
                    Text(stringResource(UiR.string.retry))
                }
                OutlinedButton(onClick = { actions.showSolution() }) {
                    Text(stringResource(R.string.puzzle_show_solution))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { actions.loadRandom(state.difficulty) }) {
        Text(stringResource(R.string.puzzle_next))
    }
}

@Composable
private fun PuzzleDifficultySelector(state: PuzzleUiState, actions: PuzzleActions) {
    SingleChoiceSegmentedButtonRow {
        val labels = listOf(
            stringResource(UiR.string.easy),
            stringResource(UiR.string.medium),
            stringResource(UiR.string.hard)
        )
        PuzzleDifficulty.entries.forEachIndexed { idx, diff ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(idx, PUZZLE_DIFFICULTY_COUNT),
                onClick = { if (state.difficulty != diff) actions.loadRandom(diff) },
                selected = state.difficulty == diff,
                label = { Text(labels[idx], style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun PuzzleStatusText(status: PuzzleStatus) {
    val statusText = when (status) {
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
}
