package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.dao.AchievementWithProgress
import com.vayunmathur.games.hub.domain.KnownGames
import com.vayunmathur.games.hub.ui.components.AchievementRow
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.hub.R

enum class AchievementFilter { ALL, LOCKED, UNLOCKED }

@Composable
fun AchievementsScreen(
    viewModel: GameHubViewModel,
    modifier: Modifier = Modifier,
    initialGameFilter: String? = null
) {
    val allAchievements by viewModel.allAchievementsFlow.collectAsStateWithLifecycle()
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()

    // Saveable so search and filters survive the tab pager disposing this page on a swipe
    // (see GamesListScreen), plus rotation and process death.
    var search by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf(AchievementFilter.ALL) }
    var gameFilter by rememberSaveable { mutableStateOf(initialGameFilter) }

    val gameNames = remember(games) { games.associate { it.gameId to it.displayName } }
    fun nameOf(gameId: String) = KnownGames.displayName(gameId, gameNames[gameId])

    val gameIds = remember(games, allAchievements) {
        (games.map { it.gameId } + allAchievements.map { it.gameId }).distinct()
            .sortedBy { KnownGames.displayName(it, gameNames[it]).lowercase() }
    }

    val filtered = remember(allAchievements, search, statusFilter, gameFilter, gameNames) {
        var list = allAchievements
        if (search.isNotBlank()) {
            list = list.filter {
                it.name.contains(search, true) || it.description.contains(search, true) ||
                    nameOf(it.gameId).contains(search, true)
            }
        }
        list = when (statusFilter) {
            AchievementFilter.LOCKED -> list.filter { !it.isUnlocked }
            AchievementFilter.UNLOCKED -> list.filter { it.isUnlocked }
            AchievementFilter.ALL -> list
        }
        if (gameFilter != null) list = list.filter { it.gameId == gameFilter }
        list.sortedWith(compareBy<AchievementWithProgress> { !it.isUnlocked }.thenBy { it.gameId }.thenBy { it.name })
    }

    AppScaffold(title = { CommonSearchBar(value = search, onValueChange = { search = it }, padding = PaddingValues(0.dp)) }, scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = statusFilter == AchievementFilter.ALL, onClick = { statusFilter = AchievementFilter.ALL }, label = { Text(stringResource(UiR.string.all)) }) }
                    item { FilterChip(selected = statusFilter == AchievementFilter.UNLOCKED, onClick = { statusFilter = AchievementFilter.UNLOCKED }, label = { Text(stringResource(R.string.filter_unlocked)) }) }
                    item { FilterChip(selected = statusFilter == AchievementFilter.LOCKED, onClick = { statusFilter = AchievementFilter.LOCKED }, label = { Text(stringResource(R.string.filter_locked)) }) }
                }
            }
            if (gameIds.isNotEmpty()) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = gameFilter == null, onClick = { gameFilter = null }, label = { Text(stringResource(R.string.all_games)) }) }
                        items(gameIds, key = { it }) { gid ->
                            FilterChip(selected = gameFilter == gid, onClick = { gameFilter = if (gameFilter == gid) null else gid }, label = { Text(nameOf(gid)) })
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Text(stringResource(R.string.no_matching_achievements), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            }
            items(filtered, key = { "${it.gameId}:${it.achievementId}" }) { ach -> AchievementRow(item = ach, showGameTag = true, gameNames = gameNames) }
        }
    }
}
