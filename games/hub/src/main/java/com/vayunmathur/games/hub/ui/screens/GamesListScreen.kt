package com.vayunmathur.games.hub.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.ui.components.GameCard
import com.vayunmathur.games.hub.util.GamesListActions
import com.vayunmathur.games.hub.util.GamesListUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.sharedContainer
import androidx.compose.ui.res.stringResource

enum class GameSort { LAST_PLAYED, MOST_PLAYED, NAME, COMPLETION }

/**
 * The games list, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview`. Search and sort stay here: they are the screen's own state, and filtering the
 * list is pure. [iconFor] is the one thing that needs a device — see [DashboardScreen].
 *
 * [ownsGameMorphKeys] is false while the Home tab is the settled page: the same game appears in
 * both tabs, so only one of the two may be the origin of the morph into the game's detail page.
 */
@Composable
fun GamesListScreen(
    state: GamesListUiState,
    actions: GamesListActions,
    modifier: Modifier = Modifier,
    iconFor: (HubGameEntity) -> Drawable? = { null },
    ownsGameMorphKeys: Boolean = true,
) {
    // rememberSaveable, not remember: the tab pager disposes this page when the user swipes
    // two tabs away, and plain remember would come back with an empty search and default sort.
    // The pager's saveable-state holder keeps these across that, and across process death.
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(GameSort.LAST_PLAYED) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredSorted = remember(state.games, search, sort, state.achievementProgressByGame) {
        var list = if (search.isBlank()) state.games else state.games.filter { it.displayName.contains(search, true) || it.gameId.contains(search, true) }
        when (sort) {
            GameSort.LAST_PLAYED -> list.sortedByDescending { it.lastPlayedAt ?: 0L }
            GameSort.MOST_PLAYED -> list.sortedByDescending { it.totalPlaytimeMs }
            GameSort.NAME -> list.sortedBy { it.displayName }
            GameSort.COMPLETION -> list.sortedByDescending { game ->
                val (unlocked, total) = state.achievementProgressByGame[game.gameId] ?: (0 to 1)
                if (total == 0) 0f else unlocked.toFloat() / total.toFloat().coerceAtLeast(1f)
            }
        }
    }

    AppScaffold(
        title = {
            CommonSearchBar(
                value = search,
                onValueChange = { search = it },
                placeholder = stringResource(com.vayunmathur.games.hub.R.string.search_games),
                padding = PaddingValues(0.dp),
            )
        },
        actions = {
            IconButton(onClick = { showSortMenu = true }) { IconMoreVert() }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.sort_last_played)) }, onClick = { sort = GameSort.LAST_PLAYED; showSortMenu = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.sort_most_played)) }, onClick = { sort = GameSort.MOST_PLAYED; showSortMenu = false })
                DropdownMenuItem(text = { Text(stringResource(UiR.string.name)) }, onClick = { sort = GameSort.NAME; showSortMenu = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.sort_completion)) }, onClick = { sort = GameSort.COMPLETION; showSortMenu = false })
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filteredSorted.isEmpty()) {
                item { Text(text = if (search.isNotEmpty()) stringResource(R.string.no_matching_games) else stringResource(R.string.no_games_registered_yet_nplay_a_game_to), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 32.dp)) }
            }
            items(filteredSorted, key = { it.gameId }) { game ->
                GameCard(
                    game = game, isInstalled = game.gameId in state.installedGameIds,
                    modifier = if (!ownsGameMorphKeys) Modifier
                    else Modifier.sharedContainer("hub-game-${game.gameId}"),
                    achievementProgress = state.achievementProgressByGame[game.gameId],
                    dailyStreak = state.dailyStreakByGame[game.gameId],
                    iconDrawable = iconFor(game),
                    onClick = { actions.openGame(game.gameId) },
                    onPlay = { actions.playGame(game) }
                )
            }
        }
    }
}
