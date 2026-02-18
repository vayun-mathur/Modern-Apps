package com.vayunmathur.games.unblockjam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.unblockjam.ui.theme.UnblockJamTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var completedLevelsRepository: CompletedLevelsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LevelData.init(this)
        completedLevelsRepository = CompletedLevelsRepository(this)
        setContent {
            UnblockJamTheme {
                GameScreen(completedLevelsRepository)
            }
        }
    }
}

@Composable
fun GameScreen(completedLevelsRepository: CompletedLevelsRepository) {
    var levelIndex by remember { mutableStateOf(0) }
    var currentLevelData by remember { mutableStateOf(LevelData.LEVELS[levelIndex]) }
    val history = remember { mutableStateListOf<LevelData>() }
    var moves by remember { mutableStateOf(0) }
    var isLevelWon by remember { mutableStateOf(false) }
    var levelStats by remember { mutableStateOf(completedLevelsRepository.getLevelStats()) }

    fun changeLevel(newLevelIndex: Int) {
        levelIndex = newLevelIndex.coerceIn(0, LevelData.LEVELS.size - 1)
        currentLevelData = LevelData.LEVELS[levelIndex]
        history.clear()
        moves = 0
        isLevelWon = false
    }

    LaunchedEffect(Unit) {
        while(levelIndex.toString() in levelStats) {
            levelIndex++
            if(levelIndex == LevelData.LEVELS.size) {
                levelIndex = 0
                currentLevelData = LevelData.LEVELS[0]
                break
            }
            currentLevelData = LevelData.LEVELS[levelIndex]
        }
    }

    LaunchedEffect(isLevelWon) {
        if (isLevelWon) {
            completedLevelsRepository.updateBestScore(levelIndex, moves)
            levelStats = completedLevelsRepository.getLevelStats() // Refresh stats
            delay(1000)
            changeLevel(levelIndex + 1)
        }
    }

    Scaffold { innerPadding ->
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
                        isCompleted = currentLevelStats != null
                    )
                    MovesInfoBox(
                        moves = moves,
                        bestScore = currentLevelStats?.bestScore,
                        optimalMoves = currentLevelData.optimalMoves
                    )
                }
                GameBoard(
                    levelData = currentLevelData,
                    onLevelChanged = { newLevelData ->
                        if (!isLevelWon) {
                            history.add(currentLevelData)
                            currentLevelData = newLevelData
                            moves++
                        }
                    },
                    onLevelWon = {
                        if (!isLevelWon) {
                            moves++
                            isLevelWon = true
                        }
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
                                currentLevelData = history.removeAt(history.size - 1)
                                moves--
                            }
                        },
                        enabled = history.isNotEmpty() && !isLevelWon
                    ) {
                        Text("Undo")
                    }
                    Button(onClick = {
                        history.clear()
                        currentLevelData = LevelData.LEVELS[levelIndex]
                        moves = 0
                        isLevelWon = false
                    },
                        enabled = history.isNotEmpty() && !isLevelWon) {
                        Text("Restart")
                    }
                }
            }
        }
    }
}

@Composable
fun PuzzleInfoBox(levelIndex: Int, onLevelChange: (Int) -> Unit, isCompleted: Boolean) {
    InfoBox(title = "Puzzle") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { onLevelChange(levelIndex - 1) }) {
                Icon(
                    painterResource(R.drawable.arrow_back_24px),
                    contentDescription = "Previous Level",
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${levelIndex + 1}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = { onLevelChange(levelIndex + 1) }) {
                Icon(
                    painterResource(R.drawable.arrow_forward_24px),
                    contentDescription = "Next Level",
                )
            }
        }
        if (isCompleted) {
            Text(
                text = "Completed",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MovesInfoBox(moves: Int, bestScore: Int?, optimalMoves: Int) {
    InfoBox(title = "Moves") {
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
        shape = RoundedCornerShape(8.dp),
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
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
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
                .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp))
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
                        .offset(currentOffsetX, offsetY)
                        .padding(4.dp)
                        .background(color, shape = RoundedCornerShape(4.dp))
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
                                        onLevelChanged(levelData.copy(blocks = newBlocks))
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
