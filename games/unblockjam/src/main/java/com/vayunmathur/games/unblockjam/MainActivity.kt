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
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
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
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.games.unblockjam.ui.UnblockJamTheme
import com.vayunmathur.games.unblockjam.util.AppBackupAgent
import com.vayunmathur.games.unblockjam.util.UnblockJamViewModel
import com.vayunmathur.games.unblockjam.util.blockDragGestures
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

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
                PackScreen(backStack, onOpenGameCenter = { backStack.add(Route.GameCenter) })
            }
            entry<Route.LevelSelector> {
                val pack = LevelPack.PACKS[it.packIndex]
                UnblockJamTheme(pack = pack) {
                    LevelScreen(backStack, viewModel, it.packIndex)
                }
            }
            entry<Route.Game> {
                val pack = LevelPack.PACKS[it.packIndex]
                UnblockJamTheme(pack = pack) {
                    GameScreen(backStack, viewModel, it.packIndex, it.levelIndex)
                }
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
fun PackScreen(backStack: NavBackStack<Route>, onOpenGameCenter: () -> Unit) {
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
            itemsIndexed(LevelPack.PACKS) { index, pack ->
                Card(Modifier.clickable{
                    backStack.add(Route.LevelSelector(index))
                }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(pack.name, Modifier.align(Alignment.Center), style = MaterialTheme.typography.displayMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelScreen(backStack: NavBackStack<Route>, viewModel: UnblockJamViewModel, packIndex: Int) {
    val pack = LevelPack.PACKS[packIndex]
    val levelStats by viewModel.levelStats.collectAsState()
    Scaffold(topBar = {
        TopAppBar({Text(stringResource(R.string.level_selector))})
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
                    Modifier.fillMaxWidth().clickable { backStack.add(Route.Game(packIndex, index)) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(backStack: NavBackStack<Route>, viewModel: UnblockJamViewModel, packIndex: Int, levelIndex: Int) {
    val pack = LevelPack.PACKS[packIndex]
    val uiState by viewModel.uiState.collectAsState()
    val levelStats by viewModel.levelStats.collectAsState()

    LaunchedEffect(packIndex, levelIndex) {
        viewModel.loadLevel(packIndex, levelIndex)
    }

    LaunchedEffect(packIndex, levelIndex) {
        viewModel.nextLevel.collect { nextIndex ->
            val boundedIndex = nextIndex.coerceIn(0, pack.levels.lastIndex)
            backStack.setLast(Route.Game(packIndex, boundedIndex))
        }
    }

    val isReady = uiState.packIndex == packIndex &&
            uiState.levelIndex == levelIndex &&
            uiState.currentLevelData != null
    val currentLevelData = if (isReady) uiState.currentLevelData!! else pack.levels[levelIndex]
    val isLevelWon = isReady && uiState.isLevelWon

    Scaffold(topBar = {TopAppBar({}, navigationIcon = {IconNavigation(backStack)})}) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val infoBoxes = @Composable {
                val currentLevelStats = pack.levels.getOrNull(levelIndex)?.id?.let { levelStats[it] }
                PuzzleInfoBox(
                    levelIndex = levelIndex,
                    onLevelChange = { newIndex ->
                        val bounded = newIndex.coerceIn(0, pack.levels.lastIndex)
                        backStack.setLast(Route.Game(packIndex, bounded))
                    },
                    isCompleted = currentLevelStats != null,
                    maxLevelIndex = pack.levels.lastIndex
                )
                MovesInfoBox(
                    moves = if (isReady) viewModel.getCurrentMoves() else 0,
                    bestScore = currentLevelStats?.bestScore,
                    optimalMoves = currentLevelData.optimalMoves
                )
            }
            val actionButtons = @Composable {
                val hasHistory = isReady && uiState.history.isNotEmpty()
                Button(
                    onClick = { viewModel.onUndo() },
                    enabled = hasHistory && !isLevelWon
                ) {
                    Text(stringResource(R.string.undo))
                }
                Button(
                    onClick = { viewModel.onRestart() },
                    enabled = hasHistory && !isLevelWon
                ) {
                    Text(stringResource(R.string.restart))
                }
            }
            val board = @Composable { boardModifier: Modifier ->
                GameBoard(
                    levelData = currentLevelData,
                    onLevelChanged = viewModel::onBlockMoved,
                    onLevelWon = viewModel::onLevelWon,
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

                Box(
                    modifier = Modifier
                        .size(blockWidth, blockHeight)
                        .offset { IntOffset(currentOffsetX.roundToPx(), offsetY.roundToPx()) }
                        .padding(scaling * 4)
                        .background(color, shape = RoundedCornerShape(percent = 10))
                        .blockDragGestures(
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
                )
            }
        }
    }
    }
}
