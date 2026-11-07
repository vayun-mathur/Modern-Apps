package com.vayunmathur.games.chess

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.chess.data.LearnCategory
import com.vayunmathur.games.chess.data.square
import com.vayunmathur.games.chess.util.LearnProgress
import com.vayunmathur.games.chess.util.LearnStatus
import com.vayunmathur.games.chess.util.LearnUiState
import com.vayunmathur.games.chess.util.LearnViewModel
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
    // No app bar: the tab has no title worth showing and no actions, so the content gets the space.
    // RAW SCAFFOLD EXCEPTION: bar-less game list; the shared scaffolds all impose a top app bar.
    Scaffold { pad ->
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
                top = pad.calculateTopPadding(),
                bottom = pad.calculateBottomPadding() + 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            cats.forEachIndexed { index, cat ->
                item(key = "cat_${cat.key}") {
                    Text(
                        cat.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        // Separates one category from the previous one; the first has nothing above
                        // it now that the app bar is gone.
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp)
                    )
                }
                items(cat.stages, key = { "stage_${it.key}" }) { stage ->
                    StageRow(
                        context = context,
                        catKey = cat.key,
                        stage = stage,
                        onOpenStage = onOpenStage
                    )
                }
            }
        }
    }
}

@Composable
private fun StageRow(
    context: android.content.Context,
    catKey: String,
    stage: com.vayunmathur.games.chess.data.LearnStage,
    onOpenStage: (String, String) -> Unit,
) {
    val done = LearnProgress.completedCount(context, stage)
    Card(Modifier.fillMaxWidth().clickable { onOpenStage(catKey, stage.key) }) {
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

    AppScaffold(
        title = stage?.title ?: stringResource(R.string.tab_learn),
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { pad ->
        if (stage == null || level == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator() }
            return@AppScaffold
        }
        LearnStageContent(
            ui = ui,
            stage = stage,
            level = level,
            viewModel = viewModel,
            pad = pad,
        )

        if (showIntro) StageIntroDialog(stage, onStart = { showIntro = false })

        // Pawn promotion picker (supports underpromotion lessons).
        if (ui.board.promotionPosition != null) {
            PawnPromotionDialog(ui.playerColor, onPromote = viewModel::onPromote)
        }

        // Stage-complete overlay after the final level (Lichess "Stage N complete").
        val isLast = ui.levelIndex + 1 >= stage.levels.size
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
private fun LearnStageContent(
    ui: LearnUiState,
    stage: com.vayunmathur.games.chess.data.LearnStage,
    level: com.vayunmathur.games.chess.data.LearnLevel,
    viewModel: LearnViewModel,
    pad: PaddingValues,
) {
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
        LearnStatusArea(ui = ui, isLast = ui.levelIndex + 1 >= stage.levels.size, viewModel = viewModel)
    }
}

@Composable
private fun LearnStatusArea(
    ui: LearnUiState,
    isLast: Boolean,
    viewModel: LearnViewModel,
) {
    Box(Modifier.height(112.dp), contentAlignment = Alignment.TopCenter) {
        when (ui.status) {
            LearnStatus.Completed -> if (!isLast) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StarRow(ui.starsEarned)
                    Text(stringResource(R.string.learn_completed), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.nextLevel() }) {
                        Text(stringResource(UiR.string.next))
                    }
                }
            }
            LearnStatus.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.puzzle_failed), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.retryLevel() }) {
                    Text(stringResource(UiR.string.retry))
                }
            }
            LearnStatus.Playing -> {}
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
                            Text("★", fontSize = 9.sp, color = fg)
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
internal fun StarRow(stars: Int) {
    Row {
        for (i in 1..MAX_LEARN_STARS) {
            Text(
                if (i <= stars) "★" else "☆",
                fontSize = 28.sp,
                color = if (i <= stars) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        }
    }
}

private fun brushColor(brush: String): Color = when (brush) {
    "red" -> Color(BRUSH_RED)
    "yellow" -> Color(BRUSH_YELLOW)
    "blue" -> Color(BRUSH_BLUE)
    else -> Color(BRUSH_GREEN) // green / paleGreen
}

private const val BRUSH_RED = 0xCCE04040
private const val BRUSH_YELLOW = 0xCCE0B020
private const val BRUSH_BLUE = 0xCC4070E0
private const val BRUSH_GREEN = 0xCC2F9E52
private const val STAR_YELLOW = 0xF0FFC107
private const val MAX_LEARN_STARS = 3
private const val BOARD_SQUARES = 8
private const val FLIPPED_ROTATION = 180f
private const val ARROW_HEAD_WIDTH_FRACTION = 0.9f
private const val ARROW_HEAD_LEN_FRACTION = 0.8f
private const val ARROW_STROKE_FRACTION = 0.18f
private const val RING_RADIUS_FRACTION = 0.42f
private const val RING_STROKE_FRACTION = 0.08f
private const val STAR_OUTER_FRACTION = 0.36f
private const val STAR_INNER_FRACTION = 0.15f

private fun DrawScope.drawLearnArrow(
    from: com.vayunmathur.games.chess.data.Position,
    to: com.vayunmathur.games.chess.data.Position,
    cell: Float,
    color: Color,
) {
    val start = Offset(from.col * cell + cell / 2, from.row * cell + cell / 2)
    val end = Offset(to.col * cell + cell / 2, to.row * cell + cell / 2)
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    if (dist == 0f) return
    val ux = dx / dist
    val uy = dy / dist
    // Arrowhead spanning ~90% of a square, matching Lichess's chunky learn arrows.
    val headWidth = cell * ARROW_HEAD_WIDTH_FRACTION
    val headLen = cell * ARROW_HEAD_LEN_FRACTION
    val baseX = end.x - ux * headLen
    val baseY = end.y - uy * headLen
    val perpX = -uy
    val perpY = ux
    val p1 = Offset(baseX + perpX * headWidth / 2, baseY + perpY * headWidth / 2)
    val p2 = Offset(baseX - perpX * headWidth / 2, baseY - perpY * headWidth / 2)
    drawLine(
        color,
        start,
        Offset(baseX, baseY),
        strokeWidth = cell * ARROW_STROKE_FRACTION,
        cap = StrokeCap.Round
    )
    val path = Path().apply {
        moveTo(end.x, end.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawStar(center: Offset, outerR: Float, innerR: Float, color: Color) {
    val path = Path()
    for (i in 0 until STAR_POINTS) {
        val r = if (i % 2 == 0) outerR else innerR
        val a = -Math.PI / STAR_HALF_DIVISOR + i * Math.PI / STAR_STEP_DIVISOR
        val x = (center.x + cos(a) * r).toFloat()
        val y = (center.y + sin(a) * r).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

private const val STAR_POINTS = 10
private const val STAR_HALF_DIVISOR = 2
private const val STAR_STEP_DIVISOR = 5

@Composable
fun LearnBoard(ui: LearnUiState, onSquareClick: (com.vayunmathur.games.chess.data.Position) -> Unit) {
    // Capped like the game board so a desktop window doesn't blow the grid up.
    Box(Modifier.widthIn(max = ChessBoardMaxWidth).fillMaxWidth().aspectRatio(1f)) {
        // Mirror LearnViewModel's legality choice so drag highlights and
        // drops accept exactly the moves a tap would on kingless /
        // illegal-move-offer lessons.
        val pseudoLegal = ui.level?.offerIllegalMove == true || ui.board.kingCount() < 2
        BoardGrid(
            board = ui.board,
            selectedPiece = ui.selectedPiece,
            isFlipped = ui.isFlipped,
            turn = ui.playerColor,
            onSquareClick = onSquareClick,
            dragEnabled = ui.status == LearnStatus.Playing,
            dragMoveLegal = if (pseudoLegal) {
                { from, to -> ui.board.isPseudoLegalMove(from, to) }
            } else {
                { from, to -> ui.board.isValidMove(from, to) }
            },
        )
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer { if (ui.isFlipped) rotationZ = FLIPPED_ROTATION }
        ) {
            val cell = size.width / BOARD_SQUARES
            ui.shapes.forEach { drawLearnShape(it, cell) }
            ui.apples.forEach { drawApple(it, cell) }
        }
    }
}

private fun DrawScope.drawLearnShape(s: com.vayunmathur.games.chess.data.LearnShape, cell: Float) {
    val color = brushColor(s.brush)
    val o = square(s.orig)
    val dest = s.dest
    if (dest != null) {
        drawLearnArrow(o, square(dest), cell, color)
    } else {
        drawCircle(
            color,
            radius = cell * RING_RADIUS_FRACTION,
            center = cellCenter(o, cell),
            style = Stroke(width = cell * RING_STROKE_FRACTION)
        )
    }
}

private fun DrawScope.drawApple(a: com.vayunmathur.games.chess.data.Position, cell: Float) {
    drawStar(
        cellCenter(a, cell),
        cell * STAR_OUTER_FRACTION,
        cell * STAR_INNER_FRACTION,
        Color(STAR_YELLOW)
    )
}

private fun cellCenter(p: com.vayunmathur.games.chess.data.Position, cell: Float): Offset =
    Offset(p.col * cell + cell / CELL_HALF_DIVISOR, p.row * cell + cell / CELL_HALF_DIVISOR)

private const val CELL_HALF_DIVISOR = 2
