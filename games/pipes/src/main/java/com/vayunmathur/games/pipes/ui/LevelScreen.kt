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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.Route
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.platform.PipesViewModel
import com.vayunmathur.games.pipes.ui.components.LevelThumbnail
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.util.LevelStats
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelScreen(backStack: NavBackStack<Route>, viewModel: PipesViewModel, packIndex: Int) {
    // See GameScreen: a stale restored pack index must not crash composition.
    val pack = LevelPack.PACKS.getOrNull(packIndex) ?: run {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading\u2026") }
        return
    }
    val levelStats by viewModel.levelStats.collectAsState()
    AppScaffold(
        title = stringResource(R.string.level_selector),
        backStack = backStack,
    ) { paddingValues ->
        LazyVerticalGrid(
            GridCells.Adaptive(GRID_CELL_MIN_DP.dp),
            Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(
                start = GRID_PADDING_DP.dp,
                top = GRID_PADDING_DP.dp,
                end = GRID_PADDING_DP.dp,
                bottom = 0.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        ) {
            itemsIndexed(pack.levels) { index, levelData ->
                LevelCell(
                    levelData = levelData,
                    index = index,
                    stat = levelStats[levelData.id],
                    onOpen = { backStack.add(Route.Game(packIndex, index)) },
                )
            }
        }
    }
}

@Composable
private fun LevelCell(
    levelData: LevelData,
    index: Int,
    stat: LevelStats?,
    onOpen: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            com.vayunmathur.library.ui.MaterialTheme.colorScheme.surface,
        ),
    ) {
        Box(Modifier.fillMaxSize().padding(CELL_PADDING_DP.dp)) {
            if (levelData.cells.size < levelData.rows * levelData.cols) {
                LevelThumbnail(
                    levelData,
                    Modifier.size(THUMBNAIL_DP.dp).align(Alignment.CenterStart),
                )
            }
            Text("${index + 1}", Modifier.align(Alignment.Center))
            Box(
                Modifier.size(RESULT_BADGE_DP.dp).align(Alignment.CenterEnd),
                Alignment.Center,
            ) {
                when {
                    stat == null -> return@Box
                    stat.bestScore <= levelData.optimalMoves -> IconStar()
                    else -> IconCheck()
                }
            }
        }
    }
}

private const val GRID_CELL_MIN_DP = 88
private const val GRID_PADDING_DP = 16
private const val GRID_SPACING_DP = 12
private const val CELL_PADDING_DP = 8
private const val THUMBNAIL_DP = 24
private const val RESULT_BADGE_DP = 20
