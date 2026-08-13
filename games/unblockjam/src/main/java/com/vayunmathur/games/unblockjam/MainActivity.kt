package com.vayunmathur.games.unblockjam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.unblockjam.data.DailyLevelGenerator
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.games.unblockjam.ui.UnblockJamTheme
import com.vayunmathur.games.unblockjam.util.AppBackupAgent
import com.vayunmathur.games.unblockjam.util.DailyProgress
import com.vayunmathur.games.unblockjam.util.GameActions
import com.vayunmathur.games.unblockjam.util.GameUiState
import com.vayunmathur.games.unblockjam.util.UnblockJamViewModel
import com.vayunmathur.games.unblockjam.util.blockDragGestures
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.LevelStats
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LevelPack.init(this)
        setContent {
            UnblockJamTheme {
                val viewModel: UnblockJamViewModel = viewModel()
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object PackSelector: Route
    @Serializable
    data class LevelSelector(val packIndex: Int): Route
    @Serializable
    data class Game(val packIndex: Int, val levelIndex: Int): Route
    @Serializable
    data object DailySelector: Route
    @Serializable
    data class DailyGame(val levelIndex: Int): Route
    @Serializable
    data object GameCenter: Route
}

@Composable
fun Navigation(viewModel: UnblockJamViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.PackSelector)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()

    GameHubComposeHook("unblockjam", viewModel.achievementsManager)

    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.PackSelector> {
                PackPage(backStack, viewModel, onOpenGameCenter = { backStack.add(Route.GameCenter) })
            }
            entry<Route.LevelSelector> {
                val pack = LevelPack.PACKS[it.packIndex]
                UnblockJamTheme(pack = pack) {
                    LevelPage(backStack, viewModel, it.packIndex)
                }
            }
            entry<Route.Game> {
                val pack = LevelPack.PACKS[it.packIndex]
                UnblockJamTheme(pack = pack) {
                    GamePage(backStack, viewModel, it.packIndex, it.levelIndex)
                }
            }
            entry<Route.DailySelector> {
                DailyLevelPage(backStack, viewModel)
            }
            entry<Route.DailyGame> {
                GamePage(backStack, viewModel, UnblockJamViewModel.DAILY_PACK_INDEX, it.levelIndex)
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

/** Binds [UnblockJamViewModel] to the stateless [PackScreen]. */
@Composable
fun PackPage(backStack: NavBackStack<Route>, viewModel: UnblockJamViewModel, onOpenGameCenter: () -> Unit) {
    val dailyCompleted by viewModel.dailyCompleted.collectAsState()
    val dailyDay by viewModel.dailyDay.collectAsState()
    val dailyStreak by viewModel.dailyStreak.collectAsState()

    PackScreen(
        packNames = LevelPack.PACKS.map { it.name },
        daily = DailyProgress(
            day = dailyDay,
            completed = dailyCompleted,
            total = DailyLevelGenerator.LEVELS_PER_DAY,
            streak = dailyStreak,
        ),
        onOpenPack = { backStack.add(Route.LevelSelector(it)) },
        onOpenDaily = { backStack.add(Route.DailySelector) },
        onOpenGameCenter = onOpenGameCenter,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackScreen(
    packNames: List<String>,
    onOpenPack: (Int) -> Unit,
    onOpenGameCenter: () -> Unit,
    daily: DailyProgress? = null,
    onOpenDaily: () -> Unit = {},
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.pack_selector)) },
            actions = {
                IconButton(onClick = onOpenGameCenter) {
                    Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
                }
            }
        )
    }) { paddingValues ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            daily?.let { item { DailyCard(it, onOpenDaily) } }
            itemsIndexed(packNames) { index, name ->
                Card(Modifier.clickable{
                    onOpenPack(index)
                }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(name, Modifier.align(Alignment.Center), style = MaterialTheme.typography.displayMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyCard(daily: DailyProgress, onOpen: () -> Unit) {
    Card(
        Modifier.clickable { onOpen() },
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    stringResource(R.string.daily_challenge),
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    LocalDate.ofEpochDay(daily.day)
                        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${daily.completed}/${daily.total}", style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.daily_streak, daily.streak.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Binds [UnblockJamViewModel] to today's daily pack, reusing the pack level grid. */
@Composable
fun DailyLevelPage(backStack: NavBackStack<Route>, viewModel: UnblockJamViewModel) {
    val dailyPack by viewModel.dailyPack.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()

    // Generates on first open, and again if the day rolled over while the app was backgrounded.
    LaunchedEffect(Unit) { viewModel.refreshDaily() }

    val pack = dailyPack
    if (pack == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LevelScreen(
        pack = pack,
        levelStats = dailyStats,
        title = stringResource(R.string.daily_challenge),
        onOpenLevel = { backStack.add(Route.DailyGame(it)) }
    )
}

/** Binds [UnblockJamViewModel] to the stateless [LevelScreen]. */
@Composable
fun LevelPage(backStack: NavBackStack<Route>, viewModel: UnblockJamViewModel, packIndex: Int) {
    val levelStats by viewModel.levelStats.collectAsState()
    LevelScreen(
        pack = LevelPack.PACKS[packIndex],
        levelStats = levelStats,
        onOpenLevel = { backStack.add(Route.Game(packIndex, it)) }
    )
}

/**
 * The puzzle grid for one pack, with no dependency on the ViewModel so it can be rendered
 * from a `@Preview` — see `src/screenshotTest`, which is where the store listing images
 * come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelScreen(
    pack: LevelPack,
    levelStats: Map<String, LevelStats>,
    onOpenLevel: (Int) -> Unit,
    title: String = stringResource(R.string.level_selector)
) {
    Scaffold(topBar = {
        TopAppBar({Text(title)})
    }) { paddingValues ->
        LazyVerticalGrid(
            GridCells.Adaptive(88.dp),
            Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(pack.levels) { index, levelData ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpenLevel(index) },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                ) {
                    Box(Modifier.fillMaxSize().padding(8.dp)) {
                        Text("${index + 1}", Modifier.align(Alignment.Center))
                        val levelStat = levelStats[levelData.id]
                        Box(
                            Modifier.size(20.dp).align(Alignment.CenterEnd),
                            Alignment.Center
                        ) {
                            when {
                                levelStat == null -> return@Box
                                levelStat.bestScore <= levelData.optimalMoves -> IconStar()
                                else -> IconCheck()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Binds [UnblockJamViewModel] to the stateless [GameScreen]. */
@Composable
fun GamePage(backStack: NavBackStack<Route>, viewModel: UnblockJamViewModel, packIndex: Int, levelIndex: Int) {
    val uiState by viewModel.uiState.collectAsState()
    val packStats by viewModel.levelStats.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()
    val dailyPack by viewModel.dailyPack.collectAsState()

    val isDaily = packIndex == UnblockJamViewModel.DAILY_PACK_INDEX
    val levels = if (isDaily) dailyPack?.levels else LevelPack.PACKS[packIndex].levels
    val levelStats = if (isDaily) dailyStats else packStats

    // Restoring straight onto a daily level can outrun pack generation.
    LaunchedEffect(isDaily) { if (isDaily) viewModel.refreshDaily() }

    LaunchedEffect(packIndex, levelIndex, levels) {
        viewModel.loadLevel(packIndex, levelIndex)
    }

    val startingLevelData = levels?.getOrNull(levelIndex)
    if (levels == null || startingLevelData == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val isReady = uiState.packIndex == packIndex &&
            uiState.levelIndex == levelIndex &&
            uiState.currentLevelData != null
    val currentLevelData = if (isReady) uiState.currentLevelData!! else startingLevelData
    val currentLevelStats = levelStats[startingLevelData.id]

    // Everything except level navigation is the ViewModel's; that one is the back stack's.
    val actions = remember(viewModel, backStack, packIndex, levels.lastIndex) {
        object : GameActions by viewModel {
            override fun onLevelChange(newIndex: Int) {
                val clamped = newIndex.coerceIn(0, levels.lastIndex)
                backStack.setLast(
                    if (isDaily) Route.DailyGame(clamped) else Route.Game(packIndex, clamped)
                )
            }
        }
    }

    GameScreen(
        state = GameUiState(
            levelData = currentLevelData,
            levelIndex = levelIndex,
            maxLevelIndex = levels.lastIndex,
            moves = if (isReady) viewModel.getCurrentMoves() else 0,
            bestScore = currentLevelStats?.bestScore,
            isCompleted = currentLevelStats != null,
            isLevelWon = isReady && uiState.isLevelWon,
            canUndo = isReady && uiState.history.isNotEmpty()
        ),
        actions = actions,
        onBack = { backStack.pop() }
    )
}

/**
 * The playable board, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(state: GameUiState, actions: GameActions, onBack: () -> Unit) {
    val currentLevelData = state.levelData
    val isLevelWon = state.isLevelWon

    Scaffold(
        topBar = { TopAppBar({}, navigationIcon = { IconNavigation(onBack) }) },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val infoBoxes = @Composable {
                PuzzleInfoBox(
                    levelIndex = state.levelIndex,
                    onLevelChange = actions::onLevelChange,
                    isCompleted = state.isCompleted,
                    maxLevelIndex = state.maxLevelIndex
                )
                MovesInfoBox(
                    moves = state.moves,
                    bestScore = state.bestScore,
                    optimalMoves = currentLevelData.optimalMoves
                )
            }
            val actionButtons = @Composable {
                // While playing show Undo/Restart; once solved they're replaced by the
                // "next level" button in the same row.
                if (!isLevelWon) {
                    Button(
                        onClick = { actions.onUndo() },
                        enabled = state.canUndo
                    ) {
                        Text(stringResource(UiR.string.undo))
                    }
                    Button(
                        onClick = { actions.onRestart() },
                        enabled = state.canUndo
                    ) {
                        Text(stringResource(R.string.restart))
                    }
                } else if (state.levelIndex < state.maxLevelIndex) {
                    Button(onClick = { actions.onLevelChange(state.levelIndex + 1) }) {
                        Text(stringResource(R.string.next_level))
                    }
                }
            }
            val board = @Composable { boardModifier: Modifier ->
                GameBoard(
                    levelData = currentLevelData,
                    onLevelChanged = actions::onBlockMoved,
                    onLevelWon = actions::onLevelWon,
                    isLevelWon = isLevelWon,
                    modifier = boardModifier
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                if (maxWidth > maxHeight) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            board(Modifier.fillMaxSize())
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            infoBoxes()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                actionButtons()
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            infoBoxes()
                        }
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            board(Modifier.fillMaxSize())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            actionButtons()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PuzzleInfoBox(levelIndex: Int, onLevelChange: (Int) -> Unit, isCompleted: Boolean, maxLevelIndex: Int) {
    InfoBox(title = stringResource(R.string.level)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                onClick = { onLevelChange(levelIndex - 1) },
                enabled = levelIndex > 0
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.previous_level),
                )
            }
            Text(
                text = "${levelIndex + 1}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = { onLevelChange(levelIndex + 1) },
                enabled = levelIndex < maxLevelIndex
            ) {
                Icon(
                    painterResource(R.drawable.arrow_forward_24px),
                    contentDescription = stringResource(R.string.next_level),
                )
            }
        }
        if (isCompleted) {
            Text(
                text = stringResource(R.string.completed),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MovesInfoBox(moves: Int, bestScore: Int?, optimalMoves: Int) {
    InfoBox(title = stringResource(R.string.moves)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$moves",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${bestScore ?: "-"} / $optimalMoves",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun InfoBox(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.size(width = 150.dp, height = 120.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround
        ) {
            Text(text = title, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun GameBoard(
    levelData: LevelData,
    onLevelChanged: (LevelData) -> Unit,
    onLevelWon: () -> Unit,
    isLevelWon: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
    val boardSize = minOf(maxWidth, maxHeight)
    val cellWidth = boardSize / levelData.dimension.width
    val cellHeight = boardSize / levelData.dimension.height

    // scale values based on the level's dimensions for consistent visuals
    val scaling = boardSize / minOf(levelData.dimension.width, levelData.dimension.height) / 100

    // make sure the exit can cover the whole main block
    val exitWidthMult = 1 + levelData.blocks[0].dimension.width

    Box {
        Box(
            Modifier
                .size(cellWidth * exitWidthMult + 1.dp, cellHeight)
                .offset(boardSize - 1.dp, cellHeight * levelData.exit.y)
                .background(MaterialTheme.colorScheme.primary)
        )
        Box(
            Modifier
                .size(cellWidth * exitWidthMult, cellHeight)
                .offset(boardSize, cellHeight * levelData.exit.y)
                .zIndex(1f)
                .background(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            1f / exitWidthMult to MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        Box(
            Modifier
                .size(boardSize)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(scaling * 12))
        ) {

            levelData.blocks.forEachIndexed { index, block ->
                val isMainBlock = index == 0
                val color = when {
                    isMainBlock -> MaterialTheme.colorScheme.tertiary
                    block.fixed -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
                val blockWidth = cellWidth * block.dimension.width
                val blockHeight = cellHeight * block.dimension.height

                var offsetX by remember(block, levelData) { mutableStateOf(cellWidth * block.position.x) }
                var offsetY by remember(block, levelData) { mutableStateOf(cellHeight * block.position.y) }

                val targetOffsetX = if (isMainBlock && isLevelWon) boardSize + cellWidth else offsetX
                val currentOffsetX by animateDpAsState(
                    targetOffsetX,
                    tween(if (isMainBlock && isLevelWon) 600 else 0),
                    "blockOffset"
                )

                var modifier = Modifier
                    .size(blockWidth, blockHeight)
                    .offset { IntOffset(currentOffsetX.roundToPx(), offsetY.roundToPx()) }
                    .padding(scaling * 4)
                    .background(color, shape = RoundedCornerShape(percent = 10))
                
                if (!block.fixed) {
                    modifier = modifier.blockDragGestures(
                        block = block,
                        levelData = levelData,
                        isLevelWon = isLevelWon,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        isMainBlock = isMainBlock,
                        onLevelWon = onLevelWon,
                        onLevelChanged = onLevelChanged,
                        index = index,
                        offsetXProvider = { offsetX },
                        offsetYProvider = { offsetY },
                        offsetXUpdater = { offsetX = it },
                        offsetYUpdater = { offsetY = it }
                    )
                }

                Box(modifier = modifier)
            }
        }
    }
    }
}
