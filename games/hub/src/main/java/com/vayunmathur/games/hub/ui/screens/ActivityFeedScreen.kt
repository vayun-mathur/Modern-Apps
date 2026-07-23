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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.MainRoute
import com.vayunmathur.games.hub.ui.components.ActivityItemCard
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack

@Composable
fun ActivityFeedScreen(
    viewModel: GameHubViewModel,
    backStack: NavBackStack<MainRoute>? = null,
    onGameClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val activity by viewModel.allActivityFlow.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Activity") },
            navigationIcon = { backStack?.let { IconNavigation(it) } }
        )
    }) { padding ->
        if (activity.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text("No activity yet — start playing games to see your feed!", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(activity, key = { it.id }) { event -> ActivityItemCard(event = event, onGameClick = onGameClick) }
            }
        }
    }
}
