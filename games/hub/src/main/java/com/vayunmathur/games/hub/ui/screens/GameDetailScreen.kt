package com.vayunmathur.games.hub.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.MainRoute
import com.vayunmathur.games.hub.ui.components.AchievementRow
import com.vayunmathur.games.hub.ui.components.ActivityItemCard
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.StreakCard
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.util.formatDurationMs
import com.vayunmathur.games.hub.util.formatPlaytime
import com.vayunmathur.games.hub.util.formatRelativeTime
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.hub.R

/** The game id whose detail offers the hub-hosted phone controller. */
private const val KART_GAME_ID = "kart"

@Composable
fun GameDetailScreen(
    gameId: String,
    viewModel: GameHubViewModel,
    backStack: NavBackStack<MainRoute>,
    modifier: Modifier = Modifier
) {
    val game by viewModel.getGameFlow(gameId).collectAsStateWithLifecycle(initialValue = null)
    val achievements by viewModel.getAchievementsForGameFlow(gameId).collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by viewModel.getSessionsForGameFlow(gameId).collectAsStateWithLifecycle(initialValue = emptyList())
    val activity by viewModel.getActivityForGameFlow(gameId).collectAsStateWithLifecycle(initialValue = emptyList())
    val dailyStreak by viewModel.getDailyStreakForGameFlow(gameId).collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current

    val iconDrawable: Drawable? = remember(game?.packageName) { game?.packageName?.let { GameIconResolver.resolveAppIcon(context, it) } }
    val isInstalled = remember(game?.packageName) {
        try { if (game?.packageName != null) { context.packageManager.getPackageInfo(game!!.packageName, 0); true } else false } catch (_: Exception) { false }
    }

    AppScaffold(
        title = game?.displayName ?: gameId,
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val g = game
        if (g == null) {
            EmptyState(
                title = stringResource(R.string.game_not_found),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@AppScaffold
        }
        val iconBmp = remember(iconDrawable) { try { iconDrawable?.toBitmap(128,128) } catch (_:Exception){ null } }

        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(modifier = Modifier.sharedContainer("hub-game-$gameId").fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        if (iconBmp != null) Image(bitmap = iconBmp.asImageBitmap(), contentDescription = g.displayName, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
                        else Text(text = g.displayName.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = g.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        g.description?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                        Text(text = g.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Button(onClick = { launchGame(context, g) }, enabled = isInstalled, modifier = Modifier.fillMaxWidth()) { Text(if (isInstalled) stringResource(R.string.play_1, g.displayName) else stringResource(R.string.not_installed)) } }
            // Kart runs on TV with phones as controllers: the hub hosts the controller pad.
            if (gameId == KART_GAME_ID) {
                item { OutlinedButton(onClick = { backStack.add(MainRoute.PlayOnTv) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.play_on_tv)) } }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.playtime), value = formatPlaytime(g.totalPlaytimeMs), modifier = Modifier.weight(1f))
                StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.sessions), value = "${sessions.size}", modifier = Modifier.weight(1f))
                StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.last), value = g.lastPlayedAt?.let { formatRelativeTime(it) } ?: "Never", modifier = Modifier.weight(1f))
            } }
            dailyStreak?.takeIf { it.currentStreak > 0 || it.longestStreak > 0 }?.let { streak ->
                item { StreakCard(currentStreak = streak.currentStreak, longestStreak = streak.longestStreak, modifier = Modifier.fillMaxWidth()) }
            }
            if (achievements.isNotEmpty()) {
                val unlocked = achievements.count { it.isUnlocked }
                item { Text(stringResource(R.string.achievements, unlocked, achievements.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(achievements, key = { it.achievementId }) { ach -> AchievementRow(item = ach, showGameTag = false) }
            }
            if (sessions.isNotEmpty()) {
                item { Text(stringResource(R.string.recent_sessions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(sessions.take(10), key = { "session-${it.id}" }) { session ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = formatRelativeTime(session.startTime), style = MaterialTheme.typography.bodySmall)
                            Text(text = session.durationMs?.let { formatDurationMs(it) } ?: stringResource(R.string.session_in_progress), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (activity.isNotEmpty()) {
                item { Text(stringResource(R.string.tab_activity), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(activity, key = { "activity-${it.id}" }) { event -> ActivityItemCard(event = event) }
            }
        }
    }
}
