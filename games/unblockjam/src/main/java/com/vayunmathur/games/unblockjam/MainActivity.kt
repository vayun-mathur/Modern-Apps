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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.games.unblockjam.ui.UnblockJamTheme
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.CompletedLevelsRepository
import com.vayunmathur.games.unblockjam.data.LevelStats
import com.vayunmathur.games.unblockjam.util.blockDragGestures
import com.vayunmathur.games.unblockjam.util.isMoveValid

class MainActivity : ComponentActivity() {

    private lateinit var completedLevelsRepository: CompletedLevelsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LevelPack.init(this)
        completedLevelsRepository = CompletedLevelsRepository(this)
        setContent {
            UnblockJamTheme {
                Navigation(completedLevelsRepository)
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
}

@Composable
fun Navigation(completedLevelsRepository: CompletedLevelsRepository) {
    val backStack = rememberNavBackStack<Route>(Route.LevelSelector(0))
    MainNavigation(backStack) {
        entry<Route.PackSelector> {
            PackScreen(backStack)
        }
        entry<Route.LevelSelector> {
            LevelScreen(backStack, completedLevelsRepository, it.packIndex)
        }
        entry<Route.Game> {
            GameScreen(backStack, completedLevelsRepository, it.packIndex, it.levelIndex)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackScreen(backStack: NavBackStack<Route>) {
    Scaffold(topBar = {
        TopAppBar({Text(stringResource(R.string.pack_selector))})
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
fun LevelScreen(backStack: NavBackStack<Route>, completedLevelsRepository: CompletedLevelsRepository, packIndex: Int) {
    val pack = LevelPack.PACKS[packIndex]
    val levelStats = completedLevelsRepository.getLevelStats()
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
fun GameScreen(backStack: NavBackStack<Route>, completedLevelsRepository: CompletedLevelsRepository, packIndex: Int, levelIndex: Int) {
    val pack = LevelPack.PACKS[packIndex]
    var currentLevelData by remember { mutableStateOf(pack.levels[levelIndex]) }
    val history = remember { mutableStateListOf<LevelData>() }
    var isLevelWon by remember { mutableStateOf(false) }
    var levelStats by remember { mutableStateOf(completedLevelsRepository.getLevelStats()) }

    fun changeLevel(newLevelIndex: Int) {
        val boundedIndex = newLevelIndex.coerceIn(0, pack.levels.lastIndex)
        backStack.setLast(Route.Game(packIndex, boundedIndex))
    }

    fun getCurrentMoves(): Int {
        val winningMoveIncrement =
            if (isLevelWon && currentLevelData.lastMovedBlockIndex != 0) 1 else 0
        return history.size + winningMoveIncrement
    }

    LaunchedEffect(isLevelWon) {
        if (isLevelWon) {
            completedLevelsRepository.updateBestScore(pack.levels[levelIndex].id, getCurrentMoves())
            levelStats = completedLevelsRepository.getLevelStats() // Refresh stats
            delay(500)
            changeLevel(levelIndex + 1)
        }
    }

    Scaffold(topBar = {TopAppBar({}, navigationIcon = {IconNavigation(backStack)})}) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentLevelStats = pack.levels.getOrNull(levelIndex)?.id?.let { levelStats[it] }
                    PuzzleInfoBox(
                        levelIndex = levelIndex,
                        onLevelChange = ::changeLevel,
                        isCompleted = currentLevelStats != null,
                        maxLevelIndex = pack.levels.lastIndex
                    )
                    MovesInfoBox(
                        moves = getCurrentMoves(),
                        bestScore = currentLevelStats?.bestScore,
                        optimalMoves = currentLevelData.optimalMoves
                    )
                }
                GameBoard(
                    levelData = currentLevelData,
                    onLevelChanged = { newLevelData ->
                        if (history.isNotEmpty()) {
                            // If block is moved back to its previous position
                            if (history.last().blocks == newLevelData.blocks) {
                                currentLevelData = history.removeAt(history.lastIndex)
                                return@GameBoard
                            }
                        }

                        if (!isLevelWon) {
                            // Only add to history if a different block is moved
                            if (newLevelData.lastMovedBlockIndex != currentLevelData.lastMovedBlockIndex) {
                                history.add(currentLevelData)
                            }
                            currentLevelData = newLevelData
                        }
                    },
                    onLevelWon = {
                        isLevelWon = true
                    },
                    isLevelWon = isLevelWon
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            if (history.isNotEmpty()) {
                                currentLevelData = history.removeAt(history.lastIndex)
                            }
                        },
                        enabled = history.isNotEmpty() && !isLevelWon
                    ) {
                        Text(stringResource(R.string.undo))
                    }
                    Button(
                        onClick = {
                            history.clear()
                            currentLevelData = pack.levels[levelIndex]
                            isLevelWon = false
                        },
                        enabled = history.isNotEmpty() && !isLevelWon
                    ) {
                        Text(stringResource(R.string.restart))
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${levelIndex + 1}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
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
    isLevelWon: Boolean
) {
    val screenWidth = LocalWindowInfo.current.containerDpSize.width
    val boardSize = screenWidth - 32.dp
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
                val color = if (isMainBlock)
                    MaterialTheme.colorScheme.error
                else if (block.fixed)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    MaterialTheme.colorScheme.primaryContainer
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