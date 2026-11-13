package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vayunmathur.games.pipes.Route
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.domain.BoardStatus
import com.vayunmathur.games.pipes.platform.GameBoardUiState
import com.vayunmathur.games.pipes.platform.PipesGameState
import com.vayunmathur.games.pipes.platform.PipesViewModel
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.util.NavBackStack

@Composable
fun GameScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel, packIndex: Int, levelIndex: Int) {
    val uiState by viewModel.uiState.collectAsState()
    val packStats by viewModel.levelStats.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()
    val dailyPack by viewModel.dailyPack.collectAsState()
    val colorblind by viewModel.colorblind.collectAsState()
    val isDaily = packIndex == PipesViewModel.DAILY_PACK_INDEX
    val levels = if (isDaily) dailyPack?.levels else LevelPack.PACKS[packIndex].levels
    val levelStats = if (isDaily) dailyStats else packStats
    LaunchedEffect(isDaily) { if (isDaily) viewModel.refreshDaily() }
    LaunchedEffect(packIndex, levelIndex, levels) { viewModel.loadLevel(packIndex, levelIndex) }
    val levelData = levels?.getOrNull(levelIndex)
    if (levels == null || levelData == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    val isReady = uiState.packIndex == packIndex && uiState.levelIndex == levelIndex && uiState.levelData != null
    val currentLevelStats = levelStats[levelData.id]
    GameBoardScreen(state = GameBoardUiState(levelData = if (isReady) uiState.levelData!! else levelData, levelIndex = levelIndex, maxLevelIndex = levels.lastIndex, gameState = if (isReady) uiState.gameState else PipesGameState(), activeColor = if (isReady && uiState.leftStart) uiState.activeColor else null, activePath = if (isReady) uiState.activePath else emptyList(), isLevelWon = isReady && uiState.isLevelWon, isCompleted = currentLevelStats != null, colorblind = colorblind, moves = if (isReady) viewModel.getCurrentMoves() else 0, bestScore = currentLevelStats?.bestScore, canUndo = isReady && uiState.history.isNotEmpty(), showFillHint = isReady && !uiState.isLevelWon && uiState.activeColor == null && BoardStatus.needsFill(levelData, uiState.gameState)), actions = viewModel, onBack = { backStack.pop() }, onBackToPacks = { while (backStack.backStack.size > 1 && backStack.last() !is Route.PackSelector) backStack.pop() }, onLevelChange = { newIndex -> val clamped = newIndex.coerceIn(0, levels.lastIndex); backStack.setLast(if (isDaily) Route.DailyGame(clamped) else Route.Game(packIndex, clamped)) })
}
