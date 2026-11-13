package com.vayunmathur.games.hub.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.util.DashboardActions
import com.vayunmathur.games.hub.util.DashboardUiState
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel

/** Binds [GameHubViewModel] to the stateless [DashboardScreen]. */
@Composable
fun DashboardPage(
    viewModel: GameHubViewModel,
    onGameClick: (String) -> Unit,
    onProfileClick: () -> Unit,
    onActivityClick: () -> Unit,
    onGamesClick: () -> Unit,
    onControllerClick: () -> Unit,
    modifier: Modifier = Modifier,
    ownsGameMorphKeys: Boolean = true
) {
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()
    val crossStats by viewModel.statsFlow.collectAsStateWithLifecycle()
    val xp by viewModel.totalXpFlow.collectAsStateWithLifecycle()
    val level by viewModel.levelFlow.collectAsStateWithLifecycle()
    val title by viewModel.titleFlow.collectAsStateWithLifecycle()
    val profile by viewModel.profileFlow.collectAsStateWithLifecycle()
    val profileYuu by viewModel.profileYuuFlow.collectAsStateWithLifecycle()
    val recentActivity by viewModel.recentActivityFlow.collectAsStateWithLifecycle()
    val allAchievements by viewModel.allAchievementsFlow.collectAsStateWithLifecycle()
    val dailyStreaks by viewModel.dailyStreaksFlow.collectAsStateWithLifecycle()
    val sessions by viewModel.sessionsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val iconCache = remember { mutableMapOf<String, Drawable?>() }

    // Checked off the main thread and again on every resume (see rememberInstalledGameIds).
    // Until the first check lands, registered games count as installed - they were when they
    // registered - so cards do not flash "Not installed"; Play re-checks at tap time anyway.
    val packagesByGameId = remember(games) { games.associate { it.gameId to it.packageName } }
    val installedGameIds = rememberInstalledGameIds(packagesByGameId) ?: packagesByGameId.keys
    val openGame = rememberGameOpener()

    val recentlyPlayedGames = remember(sessions, games) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        sessions.filter { it.startTime >= cutoff }
            .distinctBy { it.gameId }
            .mapNotNull { session -> games.find { g -> g.gameId == session.gameId } }
            .take(10)
    }

    val gameNames = remember(games) { games.associate { it.gameId to it.displayName } }

    val achievementProgressByGame = remember(allAchievements) {
        allAchievements.groupBy { it.gameId }.mapValues { (_, list) -> list.count { it.isUnlocked } to list.size }
    }

    DashboardScreen(
        state = DashboardUiState(
            playerName = profile?.displayName,
            avatarSymbol = profile?.avatarSymbol,
            profileYuu = profileYuu,
            level = level,
            title = title,
            totalXp = xp,
            stats = crossStats,
            recentlyPlayed = recentlyPlayedGames,
            recentActivity = recentActivity,
            achievementProgressByGame = achievementProgressByGame,
            dailyStreakByGame = dailyStreaks,
            installedGameIds = installedGameIds,
            gameNames = gameNames,
        ),
        actions = object : DashboardActions {
            override fun openGame(gameId: String) = onGameClick(gameId)
            override fun openProfile() = onProfileClick()
            override fun openActivity() = onActivityClick()
            override fun openGamesList() = onGamesClick()
            override fun playGame(game: HubGameEntity) = openGame(game.packageName, game.displayName)
            override fun openController() = onControllerClick()
        },
        iconFor = { game -> iconCache.getOrPut(game.packageName) { GameIconResolver.resolveAppIcon(context, game.packageName) } },
        ownsGameMorphKeys = ownsGameMorphKeys,
        modifier = modifier,
    )
}
