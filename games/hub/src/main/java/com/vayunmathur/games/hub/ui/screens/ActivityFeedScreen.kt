package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.MainRoute
import com.vayunmathur.games.hub.ui.components.ActivityItemCard
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.hub.R

@Composable
fun ActivityFeedScreen(
    viewModel: GameHubViewModel,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<MainRoute>? = null,
    onGameClick: ((String) -> Unit)? = null
) {
    val activity by viewModel.allActivityFlow.collectAsStateWithLifecycle()
    val games by viewModel.gamesFlow.collectAsStateWithLifecycle()
    val gameNames = remember(games) { games.associate { it.gameId to it.displayName } }

    AppScaffold(
        title = stringResource(R.string.tab_activity),
        navigationIcon = { backStack?.let { IconNavigation(it) } },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        if (activity.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text(stringResource(R.string.no_activity_yet_start_playing_games_to_s), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(activity, key = { it.id }) { event -> ActivityItemCard(event = event, onGameClick = onGameClick, gameNames = gameNames) }
            }
        }
    }
}
