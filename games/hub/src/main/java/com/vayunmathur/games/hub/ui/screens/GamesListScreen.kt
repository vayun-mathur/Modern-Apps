package com.vayunmathur.games.hub.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar

enum class GameSort { LAST_PLAYED, MOST_PLAYED, NAME, COMPLETION }

@Composable
fun GamesListScreen(
    viewModel: GameHubViewModel,
    onGameClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()
    val allAchievements by viewModel.allAchievementsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(GameSort.LAST_PLAYED) }
    var showSortMenu by remember { mutableStateOf(false) }

    val iconCache = remember { mutableMapOf<String, Drawable?>() }
    fun getIcon(pkg: String): Drawable? = iconCache.getOrPut(pkg) { GameIconResolver.resolveAppIcon(context, pkg) }

    val installedMap = remember(games) {
        games.associate { g ->
            g.gameId to try { context.packageManager.getPackageInfo(g.packageName, 0); true } catch (_: Exception) { false }
        }
    }

    val achievementProgressByGame = remember(allAchievements) {
        allAchievements.groupBy { it.gameId }.mapValues { (_, list) -> list.count { it.isUnlocked } to list.size }
    }

    val filteredSorted = remember(games, search, sort, achievementProgressByGame) {
        var list = if (search.isBlank()) games else games.filter { it.displayName.contains(search, true) || it.gameId.contains(search, true) }
        when (sort) {
            GameSort.LAST_PLAYED -> list.sortedByDescending { it.lastPlayedAt ?: 0L }
            GameSort.MOST_PLAYED -> list.sortedByDescending { it.totalPlaytimeMs }
            GameSort.NAME -> list.sortedBy { it.displayName }
            GameSort.COMPLETION -> list.sortedByDescending { game ->
                val (unlocked, total) = achievementProgressByGame[game.gameId] ?: (0 to 1)
                if (total == 0) 0f else unlocked.toFloat() / total.toFloat().coerceAtLeast(1f)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Games") },
                actions = {
                    IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Sort") }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(text = { Text("Last played") }, onClick = { sort = GameSort.LAST_PLAYED; showSortMenu = false })
                        DropdownMenuItem(text = { Text("Most played") }, onClick = { sort = GameSort.MOST_PLAYED; showSortMenu = false })
                        DropdownMenuItem(text = { Text("Name") }, onClick = { sort = GameSort.NAME; showSortMenu = false })
                        DropdownMenuItem(text = { Text("Completion") }, onClick = { sort = GameSort.COMPLETION; showSortMenu = false })
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { CommonSearchBar(value = search, onValueChange = { search = it }, placeholder = "Search games", padding = PaddingValues(0.dp)) }
            if (filteredSorted.isEmpty()) {
                item { Text(text = if (search.isNotEmpty()) "No matching games" else "No games registered yet.\nPlay a game to see it here.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 32.dp)) }
            }
            items(filteredSorted, key = { it.gameId }) { game ->
                GameCard(
                    game = game, isInstalled = installedMap[game.gameId] ?: false,
                    achievementProgress = achievementProgressByGame[game.gameId],
                    iconDrawable = getIcon(game.packageName),
                    onClick = { onGameClick(game.gameId) },
                    onPlay = { launchGame(context, game) }
                )
            }
        }
    }
}
