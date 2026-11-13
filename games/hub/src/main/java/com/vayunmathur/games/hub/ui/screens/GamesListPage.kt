package com.vayunmathur.games.hub.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.util.GamesListActions
import com.vayunmathur.games.hub.util.GamesListUiState
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel

/** Binds [GameHubViewModel] to the stateless [GamesListScreen]. */
@Composable
fun GamesListPage(
    viewModel: GameHubViewModel,
    onGameClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    ownsGameMorphKeys: Boolean = true
) {
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()
    val allAchievements by viewModel.allAchievementsFlow.collectAsStateWithLifecycle()
    val dailyStreaks by viewModel.dailyStreaksFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val iconCache = remember { mutableMapOf<String, Drawable?>() }

    // Checked off the main thread and again on every resume (see rememberInstalledGameIds).
    // Until the first check lands, registered games count as installed - they were when they
    // registered - so cards do not flash "Not installed"; Play re-checks at tap time anyway.
    val packagesByGameId = remember(games) { games.associate { it.gameId to it.packageName } }
    val installedGameIds = rememberInstalledGameIds(packagesByGameId) ?: packagesByGameId.keys
    val openGame = rememberGameOpener()

    val achievementProgressByGame = remember(allAchievements) {
        allAchievements.groupBy { it.gameId }.mapValues { (_, list) -> list.count { it.isUnlocked } to list.size }
    }

    GamesListScreen(
        state = GamesListUiState(
            games = games,
            achievementProgressByGame = achievementProgressByGame,
            dailyStreakByGame = dailyStreaks,
            installedGameIds = installedGameIds,
        ),
        actions = object : GamesListActions {
            override fun openGame(gameId: String) = onGameClick(gameId)
            override fun playGame(game: HubGameEntity) = openGame(game.packageName, game.displayName)
        },
        iconFor = { game -> iconCache.getOrPut(game.packageName) { GameIconResolver.resolveAppIcon(context, game.packageName) } },
        ownsGameMorphKeys = ownsGameMorphKeys,
        modifier = modifier,
    )
}
