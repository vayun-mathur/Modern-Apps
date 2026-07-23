package com.vayunmathur.games.pipes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.ui.GameBoard
import com.vayunmathur.games.pipes.ui.PipesTheme
import com.vayunmathur.games.pipes.util.AppBackupAgent
import com.vayunmathur.games.pipes.util.PipesViewModel
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSettings
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
            PipesTheme {
                val viewModel: PipesViewModel = viewModel()
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object PackSelector : Route

    @Serializable
    data class LevelSelector(val packIndex: Int) : Route

    @Serializable
    data class Game(val packIndex: Int, val levelIndex: Int) : Route

    @Serializable
    data object GameCenter : Route

    @Serializable
    data object Settings : Route
}

@Composable
fun Navigation(viewModel: PipesViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.PackSelector)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()

    GameHubComposeHook("pipes", viewModel.achievementsManager)

    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.PackSelector> {
                PackScreen(backStack, viewModel, onOpenGameCenter = { backStack.add(Route.GameCenter) })
            }
            entry<Route.LevelSelector> {
                LevelScreen(backStack, viewModel, it.packIndex)
            }
            entry<Route.Game> {
                GameScreen(backStack, viewModel, it.packIndex, it.levelIndex)
            }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    backupAgent = AppBackupAgent(),
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() }
                )
            }
            entry<Route.Settings> {
                SettingsScreen(backStack, viewModel)
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
fun PackScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel, onOpenGameCenter: () -> Unit) {
    val levelStats by viewModel.levelStats.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.pack_selector)) },
            actions = {
                IconButton(onClick = { backStack.add(Route.Settings) }) {
                    IconSettings()
                }
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
                val completed = pack.levels.count { levelStats.containsKey(it.id) }
                Card(
                    Modifier.clickable { backStack.add(Route.LevelSelector(index)) },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                pack.name,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                pack.shape.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Text(
                            "$completed/${pack.levels.size}",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel, packIndex: Int) {
    val pack = LevelPack.PACKS[packIndex]
    val levelStats by viewModel.levelStats.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            { Text(stringResource(R.string.level_selector)) },
            navigationIcon = { IconNavigation(backStack) }
        )
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
                    Modifier
                        .fillMaxWidth()
                        .clickable { backStack.add(Route.Game(packIndex, index)) },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                ) {
                    Box(Modifier.fillMaxSize().padding(8.dp)) {
                        if (levelData.cells.size < levelData.rows * levelData.cols) {
                            LevelThumbnail(
                                levelData,
                                Modifier
                                    .size(24.dp)
                                    .align(Alignment.CenterStart)
                            )
                        }
                        Text("${index + 1}", Modifier.align(Alignment.Center))
                        val levelStat = levelStats[levelData.id]
                        Box(
                            Modifier
                                .size(20.dp)
                                .align(Alignment.CenterEnd),
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
fun GameScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel, packIndex: Int, levelIndex: Int) {
    val pack = LevelPack.PACKS[packIndex]
    val uiState by viewModel.uiState.collectAsState()
    val levelStats by viewModel.levelStats.collectAsState()
    val colorblind by viewModel.colorblind.collectAsState()

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
            uiState.levelData != null
    val currentLevelData = if (isReady) uiState.levelData!! else pack.levels[levelIndex]
    val isLevelWon = isReady && uiState.isLevelWon

    Scaffold(topBar = { TopAppBar({}, navigationIcon = { IconNavigation(backStack) }) }) { innerPadding ->
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
                    gameState = if (isReady) uiState.gameState else com.vayunmathur.games.pipes.util.PipesGameState(),
                    activeColor = if (isReady) uiState.activeColor else null,
                    activePath = if (isReady) uiState.activePath else emptyList(),
                    onStartDraw = viewModel::startDraw,
                    onExtendPath = viewModel::extendPath,
                    onCommitDraw = viewModel::commitDraw,
                    isLevelWon = isLevelWon,
                    colorblind = colorblind,
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
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            actionButtons()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel) {
    val colorblind by viewModel.colorblind.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconNavigation(backStack) }
        )
    }) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.colorblind_mode),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.colorblind_mode_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = colorblind, onCheckedChange = { viewModel.setColorblind(it) })
            }
        }
    }
}

@Composable
fun LevelThumbnail(levelData: com.vayunmathur.games.pipes.data.LevelData, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier) {
        val maxDim = maxOf(levelData.rows, levelData.cols)
        if (maxDim == 0 || levelData.cells.isEmpty()) return@Canvas
        val cell = size.minDimension / maxDim
        val minRow = levelData.cells.minOf { it.row }
        val minCol = levelData.cells.minOf { it.col }
        val usedRows = levelData.cells.maxOf { it.row } - minRow + 1
        val usedCols = levelData.cells.maxOf { it.col } - minCol + 1
        val offX = (size.width - usedCols * cell) / 2f - minCol * cell
        val offY = (size.height - usedRows * cell) / 2f - minRow * cell
        for (c in levelData.cells) {
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(offX + c.col * cell, offY + c.row * cell),
                size = androidx.compose.ui.geometry.Size(cell * 0.85f, cell * 0.85f)
            )
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
