package com.vayunmathur.games.hub.ui.screens

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.ui.components.ActivityItemCard
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.ui.components.LevelBadge
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.StreakCard
import com.vayunmathur.games.hub.ui.components.XpProgressBar
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.util.formatPlaytime
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar

@Composable
fun DashboardScreen(
    viewModel: GameHubViewModel,
    onGameClick: (String) -> Unit,
    onProfileClick: () -> Unit,
    onActivityClick: () -> Unit,
    onGamesClick: () -> Unit,
    dbConfigs: List<Pair<String, String>>,
    datastoreNames: List<String>,
    modifier: Modifier = Modifier
) {
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()
    val crossStats by viewModel.statsFlow.collectAsStateWithLifecycle()
    val xp by viewModel.totalXpFlow.collectAsStateWithLifecycle()
    val level by viewModel.levelFlow.collectAsStateWithLifecycle()
    val title by viewModel.titleFlow.collectAsStateWithLifecycle()
    val profile by viewModel.profileFlow.collectAsStateWithLifecycle()
    val recentActivity by viewModel.recentActivityFlow.collectAsStateWithLifecycle()
    val allAchievements by viewModel.allAchievementsFlow.collectAsStateWithLifecycle()
    val sessions by viewModel.sessionsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val iconCache = remember { mutableMapOf<String, Drawable?>() }
    fun getIcon(pkg: String): Drawable? = iconCache.getOrPut(pkg) { GameIconResolver.resolveAppIcon(context, pkg) }

    val installedMap = remember(games) {
        games.associate { g ->
            g.gameId to try { context.packageManager.getPackageInfo(g.packageName, 0); true } catch (_: Exception) { false }
        }
    }

    val recentlyPlayedGames = remember(sessions, games) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        sessions.filter { it.startTime >= cutoff }
            .distinctBy { it.gameId }
            .mapNotNull { session -> games.find { g -> g.gameId == session.gameId } }
            .take(10)
    }

    val achievementProgressByGame = remember(allAchievements) {
        allAchievements.groupBy { it.gameId }.mapValues { (_, list) -> list.count { it.isUnlocked } to list.size }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GameHub", fontWeight = FontWeight.Bold) },
                actions = { BackupButtons(dbConfigs = dbConfigs, datastoreNames = datastoreNames) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Card(onClick = onProfileClick, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LevelBadge(level = level, large = true)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = profile?.displayName ?: "Player", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(text = title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            XpProgressBar(totalXp = xp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (crossStats.currentStreak > 0 || crossStats.longestStreak > 0) {
                item { StreakCard(currentStreak = crossStats.currentStreak, longestStreak = crossStats.longestStreak, modifier = Modifier.fillMaxWidth()) }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = "Playtime", value = formatPlaytime(crossStats.totalPlaytimeMs), modifier = Modifier.weight(1f))
                    StatCard(label = "Games", value = "${crossStats.totalGames}", modifier = Modifier.weight(1f))
                    StatCard(label = "Achievements", value = "${crossStats.totalAchievementsUnlocked}/${crossStats.totalAchievements}", modifier = Modifier.weight(1f))
                }
            }

            if (recentlyPlayedGames.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Continue playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onGamesClick) { Text("See all") }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentlyPlayedGames, key = { it.gameId }) { game ->
                            GameCard(
                                game = game, isInstalled = installedMap[game.gameId] ?: true,
                                achievementProgress = achievementProgressByGame[game.gameId],
                                iconDrawable = getIcon(game.packageName),
                                onClick = { onGameClick(game.gameId) },
                                onPlay = { launchGame(context, game) },
                                modifier = Modifier.fillParentMaxWidth(0.85f)
                            )
                        }
                    }
                }
            }

            if (recentActivity.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Recent activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onActivityClick) { Text("See all") }
                    }
                }
                items(recentActivity.take(5), key = { it.id }) { event -> ActivityItemCard(event = event, onGameClick = onGameClick) }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "No activity yet — play a game!", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

fun launchGame(context: Context, game: HubGameEntity) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(game.packageName)
        if (intent != null) context.startActivity(intent)
    } catch (_: Exception) { }
}
