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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.data.dao.AchievementWithProgress
import com.vayunmathur.games.hub.ui.components.AchievementRow
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar

enum class AchievementFilter { ALL, LOCKED, UNLOCKED }

@Composable
fun AchievementsScreen(
    viewModel: GameHubViewModel,
    modifier: Modifier = Modifier,
    initialGameFilter: String? = null
) {
    val allAchievements by viewModel.allAchievementsFlow.collectAsStateWithLifecycle()
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()

    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(AchievementFilter.ALL) }
    var gameFilter by remember { mutableStateOf<String?>(initialGameFilter) }

    val gameIds = remember(games, allAchievements) {
        (games.map { it.gameId } + allAchievements.map { it.gameId }).distinct().sorted()
    }

    val filtered = remember(allAchievements, search, statusFilter, gameFilter) {
        var list = allAchievements
        if (search.isNotBlank()) {
            list = list.filter { it.name.contains(search, true) || it.description.contains(search, true) || it.gameId.contains(search, true) }
        }
        list = when (statusFilter) {
            AchievementFilter.LOCKED -> list.filter { !it.isUnlocked }
            AchievementFilter.UNLOCKED -> list.filter { it.isUnlocked }
            AchievementFilter.ALL -> list
        }
        if (gameFilter != null) list = list.filter { it.gameId == gameFilter }
        list.sortedWith(compareBy<AchievementWithProgress> { !it.isUnlocked }.thenBy { it.gameId }.thenBy { it.name })
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Achievements (${filtered.size})") }) }) { padding ->
        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { CommonSearchBar(value = search, onValueChange = { search = it }, padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = statusFilter == AchievementFilter.ALL, onClick = { statusFilter = AchievementFilter.ALL }, label = { Text("All") }) }
                    item { FilterChip(selected = statusFilter == AchievementFilter.UNLOCKED, onClick = { statusFilter = AchievementFilter.UNLOCKED }, label = { Text("Unlocked") }) }
                    item { FilterChip(selected = statusFilter == AchievementFilter.LOCKED, onClick = { statusFilter = AchievementFilter.LOCKED }, label = { Text("Locked") }) }
                }
            }
            if (gameIds.isNotEmpty()) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = gameFilter == null, onClick = { gameFilter = null }, label = { Text("All games") }) }
                        items(gameIds, key = { it }) { gid ->
                            FilterChip(selected = gameFilter == gid, onClick = { gameFilter = if (gameFilter == gid) null else gid }, label = { Text(gid) })
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Text(text = "No matching achievements", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            }
            items(filtered, key = { "${it.gameId}:${it.achievementId}" }) { ach -> AchievementRow(item = ach, showGameTag = true) }
        }
    }
}
