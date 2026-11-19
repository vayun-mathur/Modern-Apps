package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.pipes.Route
import com.vayunmathur.games.pipes.platform.PipesViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLevelScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel) {
    val dailyPack by viewModel.dailyPack.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshDaily() }
    AppScaffold(title = stringResource(UiR.string.daily_challenge), backStack = backStack) { paddingValues ->
        val levels = dailyPack?.levels
        if (levels == null) { Box(Modifier.fillMaxSize().padding(paddingValues), Alignment.Center) { CircularProgressIndicator() }; return@AppScaffold }
        LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxSize(), contentPadding = paddingValues + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(levels) { index, levelData -> Card(Modifier.fillMaxWidth().clickable { backStack.add(Route.DailyGame(index)) }, colors = CardDefaults.cardColors(com.vayunmathur.library.ui.MaterialTheme.colorScheme.surface)) { Box(Modifier.fillMaxSize().padding(8.dp)) { Text("${index + 1}", Modifier.align(Alignment.Center)); val levelStat = dailyStats[levelData.id]; Box(Modifier.size(20.dp).align(Alignment.CenterEnd), Alignment.Center) { when { levelStat == null -> return@Box; levelStat.bestScore <= levelData.optimalMoves -> IconStar(); else -> IconCheck() } } } } }
        }
    }
}
