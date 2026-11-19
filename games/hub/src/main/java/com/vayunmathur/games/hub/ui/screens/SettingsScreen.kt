package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.MainRoute
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.hub.R

@Composable
fun SettingsScreen(
    viewModel: GameHubViewModel,
    backStack: NavBackStack<MainRoute>,
    modifier: Modifier = Modifier
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    AppScaffold(
        title = stringResource(UiR.string.settings),
        backStack = backStack,
    ) { padding ->
        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.data), style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.clear_hub_cache_removes_all_cached_game), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { showClearConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.clear_cache)) }
                }
            }
            item { HorizontalDivider() }
            item {
                Text(stringResource(R.string.about_gamehub), style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.gamehub_aggregates_achievements_playtime), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.package_com_vayunmathur_games_hub), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text(stringResource(R.string.clear_cache_1)) },
                text = { Text(stringResource(R.string.this_will_delete_all_cached_games_achiev)) },
                confirmButton = { Button(onClick = { viewModel.clearAllData(); showClearConfirm = false }) { Text(stringResource(UiR.string.clear)) } },
                dismissButton = { com.vayunmathur.library.ui.TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(UiR.string.cancel)) } }
            )
        }
    }
}
