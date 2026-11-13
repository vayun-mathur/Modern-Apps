package com.vayunmathur.games.hub.ui.screens

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.BuildConfig
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.ui.components.ActivityItemCard
import com.vayunmathur.games.hub.ui.components.AvatarBadge
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.StreakCard
import com.vayunmathur.games.hub.ui.components.XpProgressBar
import com.vayunmathur.games.hub.data.entities.encode
import com.vayunmathur.games.hub.ui.yuu.DASHBOARD_SURFACE_ID
import com.vayunmathur.library.avatar.components.YuuAvatar3D
import com.vayunmathur.games.hub.util.DashboardActions
import com.vayunmathur.games.hub.util.DashboardUiState
import com.vayunmathur.games.hub.util.formatPlaytime
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.sharedContainer
import androidx.compose.ui.res.stringResource

/**
 * The dashboard, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 *
 * [iconFor] and [topBarActions] are the two things that genuinely need a device: installed
 * app icons, and the backup menu's file pickers (which need an Activity). Both default to
 * nothing so a preview can leave them out.
 *
 * [ownsGameMorphKeys] is false while the Games tab is the settled page: the same game appears in
 * both tabs, so only one of the two may be the origin of the morph into the game's detail page.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
    iconFor: (HubGameEntity) -> Drawable? = { null },
    ownsGameMorphKeys: Boolean = true,
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    AppScaffold(
        title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
        actions = topBarActions,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Card(onClick = actions::openProfile, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (BuildConfig.DEV_BUILD && state.profileYuu != null) {
                            YuuAvatar3D(
                                configJson = state.profileYuu.config.encode(),
                                poseId = state.profileYuu.config.poseId,
                                surfaceId = DASHBOARD_SURFACE_ID,
                                modifier = Modifier.size(56.dp),
                                fallback = {
                                    AvatarBadge(symbol = state.avatarSymbol, large = true)
                                },
                            )
                        } else {
                            AvatarBadge(symbol = state.avatarSymbol, large = true)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = state.playerName ?: stringResource(R.string.player), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            // No separate title line: XpProgressBar's header already reads
                            // "Level N • <title>", so a second copy only repeated it (#ux-audit).
                            XpProgressBar(totalXp = state.totalXp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (state.stats.currentStreak > 0 || state.stats.longestStreak > 0) {
                item { StreakCard(currentStreak = state.stats.currentStreak, longestStreak = state.stats.longestStreak, modifier = Modifier.fillMaxWidth()) }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.playtime), value = formatPlaytime(state.stats.totalPlaytimeMs), modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.tab_games), value = "${state.stats.totalGames}", modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(com.vayunmathur.games.hub.R.string.achievements_for), value = "${state.stats.totalAchievementsUnlocked}/${state.stats.totalAchievements}", modifier = Modifier.weight(1f))
                }
            }

            if (BuildConfig.DEV_BUILD) {
                item {
                    Card(onClick = actions::openController, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.connect_to_tv), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.connect_to_tv_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (state.recentlyPlayed.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.continue_playing), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = actions::openGamesList) { Text(stringResource(R.string.see_all)) }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.recentlyPlayed, key = { it.gameId }) { game ->
                            GameCard(
                                game = game, isInstalled = game.gameId in state.installedGameIds,
                                achievementProgress = state.achievementProgressByGame[game.gameId],
                                dailyStreak = state.dailyStreakByGame[game.gameId],
                                iconDrawable = iconFor(game),
                                onClick = { actions.openGame(game.gameId) },
                                onPlay = { actions.playGame(game) },
                                modifier = Modifier.fillParentMaxWidth(0.85f).then(
                                    if (!ownsGameMorphKeys) Modifier
                                    else Modifier.sharedContainer("hub-game-${game.gameId}")
                                )
                            )
                        }
                    }
                }
            }

            if (state.recentActivity.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.recent_activity), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = actions::openActivity) { Text(stringResource(R.string.see_all)) }
                    }
                }
                items(state.recentActivity.take(5), key = { it.id }) { event -> ActivityItemCard(event = event, onGameClick = actions::openGame) }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.no_activity_yet_play_a_game), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
