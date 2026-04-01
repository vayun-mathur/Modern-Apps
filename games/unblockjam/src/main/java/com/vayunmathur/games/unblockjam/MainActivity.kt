package com.vayunmathur.games.unblockjam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.games.unblockjam.ui.theme.UnblockJamTheme
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

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
            GridCells.Adaptive(80.dp),
            Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(pack.levels) { index, levelData ->
                Card(Modifier.fillMaxWidth().aspectRatio(1f).clickable{
                    backStack.add(Route.Game(packIndex, index))
                }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.fillMaxSize().padding(8.dp)) {
                        Text("${index + 1}", Modifier.align(Alignment.Center))
                        if(levelStats[index.toString()] != null) {
                            if (levelStats[index.toString()]!!.bestScore == levelData.optimalMoves) {
                                Box(Modifier.align(Alignment.TopEnd)) {
                                    IconStar()
                                }
                            }
                            Box(Modifier.align(Alignment.BottomEnd)) {IconCheck()}
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
            delay(1000)
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
                    val currentLevelStats = levelStats[levelIndex.toString()]
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
                    Button(onClick = {
                        history.clear()
                        currentLevelData = pack.levels[levelIndex]
                        isLevelWon = false
                    },
                        enabled = history.isNotEmpty() && !isLevelWon) {
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
    val boardSize = screenWidth - 32.dp // accounting for padding
    val cellWidth = boardSize / levelData.dimension.width
    val cellHeight = boardSize / levelData.dimension.height
    // Visual for the exit
    Box {
        Box(
            modifier = Modifier
                .size(cellWidth, cellHeight)
                .offset(boardSize, cellHeight * levelData.exit.y)
                .background(MaterialTheme.colorScheme.primary)
        )

        Box(
            modifier = Modifier
                .size(boardSize)
                .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp))
        ) {


            levelData.blocks.forEachIndexed { index, block ->
                val isMainBlock = index == 0
                val color = if (isMainBlock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer
                val blockWidth = cellWidth * block.dimension.width
                val blockHeight = cellHeight * block.dimension.height

                var offsetX by remember(
                    block,
                    levelData
                ) { mutableStateOf(cellWidth * block.position.x) }
                var offsetY by remember(
                    block,
                    levelData
                ) { mutableStateOf(cellHeight * block.position.y) }

                val targetOffsetX = if (isMainBlock && isLevelWon) boardSize else offsetX
                val currentOffsetX by animateDpAsState(
                    targetValue = targetOffsetX,
                    animationSpec = tween(durationMillis = if (isMainBlock && isLevelWon) 500 else 0),
                    label = "blockOffset"
                )

                var minOffset by remember { mutableStateOf(0.dp) }
                var maxOffset by remember { mutableStateOf(0.dp) }

                Box(
                    modifier = Modifier
                        .size(blockWidth, blockHeight)
                        .offset { IntOffset(currentOffsetX.roundToPx(), offsetY.roundToPx()) }
                        .padding(4.dp)
                        .background(color, shape = RoundedCornerShape(8.dp))
                        .pointerInput(block, levelData, isLevelWon) {
                            if (isLevelWon) return@pointerInput

                            detectDragGestures(
                                onDragStart = {
                                    val otherBlocks = levelData.blocks.minus(block)

                                    fun isOccupied(x: Int, y: Int): Boolean {
                                        return otherBlocks.any {
                                            x >= it.position.x && x < it.position.x + it.dimension.width &&
                                                    y >= it.position.y && y < it.position.y + it.dimension.height
                                        }
                                    }

                                    if (block.dimension.width > block.dimension.height) { // Horizontal
                                        var minX = block.position.x
                                        var maxX = block.position.x

                                        // Find minX
                                        while (minX > 0) {
                                            var clear = true
                                            for (y in block.position.y until block.position.y + block.dimension.height) {
                                                if (isOccupied(minX - 1, y)) {
                                                    clear = false
                                                    break
                                                }
                                            }
                                            if (clear) minX-- else break
                                        }

                                        // Find maxX
                                        while (maxX + block.dimension.width < levelData.dimension.width) {
                                            var clear = true
                                            for (y in block.position.y until block.position.y + block.dimension.height) {
                                                if (isOccupied(maxX + block.dimension.width, y)) {
                                                    clear = false
                                                    break
                                                }
                                            }
                                            if (clear) maxX++ else break
                                        }

                                        if (isMainBlock && block.position.y == levelData.exit.y) {
                                            var pathToExitIsClear = true
                                            for (x in (maxX + block.dimension.width) until levelData.dimension.width) {
                                                if (isOccupied(x, block.position.y)) {
                                                    pathToExitIsClear = false
                                                    break
                                                }
                                            }
                                            if (pathToExitIsClear) {
                                                maxX = levelData.exit.x
                                            }
                                        }

                                        minOffset = cellWidth * minX
                                        maxOffset = cellWidth * maxX

                                    } else { // Vertical
                                        var minY = block.position.y
                                        var maxY = block.position.y

                                        // Find minY
                                        while (minY > 0) {
                                            var clear = true
                                            for (x in block.position.x until block.position.x + block.dimension.width) {
                                                if (isOccupied(x, minY - 1)) {
                                                    clear = false
                                                    break
                                                }
                                            }
                                            if (clear) minY-- else break
                                        }

                                        // Find maxY
                                        while (maxY + block.dimension.height < levelData.dimension.height) {
                                            var clear = true
                                            for (x in block.position.x until block.position.x + block.dimension.width) {
                                                if (isOccupied(x, maxY + block.dimension.height)) {
                                                    clear = false
                                                    break
                                                }
                                            }
                                            if (clear) maxY++ else break
                                        }
                                        minOffset = cellHeight * minY
                                        maxOffset = cellHeight * maxY
                                    }
                                },
                                onDragEnd = {
                                    val newX: Int
                                    val newY: Int

                                    if (block.dimension.width > block.dimension.height) { // Horizontal
                                        newX = (offsetX / cellWidth).roundToInt()
                                        newY = block.position.y
                                    } else { // Vertical
                                        newX = block.position.x
                                        newY = (offsetY / cellHeight).roundToInt()
                                    }

                                    val newBlock = block.copy(position = Coord(newX, newY))

                                    if (isMainBlock && block.position.y == levelData.exit.y && newX >= levelData.exit.x) {
                                        onLevelWon()
                                    } else if (newBlock.position != block.position && isMoveValid(
                                            newBlock,
                                            levelData.blocks.minus(block),
                                            levelData.dimension
                                        )
                                    ) {
                                        val newBlocks = levelData.blocks.toMutableList()
                                        newBlocks[index] = newBlock
                                        onLevelChanged(levelData.copy(blocks = newBlocks, lastMovedBlockIndex = index))
                                    } else {
                                        offsetX = cellWidth * block.position.x
                                        offsetY = cellHeight * block.position.y
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (block.dimension.width > block.dimension.height) { // Horizontal
                                        offsetX =
                                            (offsetX + dragAmount.x.toDp()).coerceIn(
                                                minOffset,
                                                maxOffset
                                            )
                                        val currentX = (offsetX / cellWidth).roundToInt()
                                        if (isMainBlock && block.position.y == levelData.exit.y && currentX + block.dimension.width - 1 >= levelData.exit.x) {
                                            onLevelWon()
                                        }
                                    } else { // Vertical
                                        offsetY =
                                            (offsetY + dragAmount.y.toDp()).coerceIn(
                                                minOffset,
                                                maxOffset
                                            )
                                    }
                                }
                            )
                        }
                )
            }
        }
    }
}

fun isMoveValid(movedBlock: Block, otherBlocks: List<Block>, dimension: Dimension): Boolean {
    if (movedBlock.position.x < 0 || movedBlock.position.y < 0) return false
    if (movedBlock.position.x + movedBlock.dimension.width > dimension.width) return false
    if (movedBlock.position.y + movedBlock.dimension.height > dimension.height) return false

    for (other in otherBlocks) {
        if (movedBlock.position.x < other.position.x + other.dimension.width &&
            movedBlock.position.x + movedBlock.dimension.width > other.position.x &&
            movedBlock.position.y < other.position.y + other.dimension.height &&
            movedBlock.position.y + movedBlock.dimension.height > other.position.y
        ) {
            return false
        }
    }
    return true
}
