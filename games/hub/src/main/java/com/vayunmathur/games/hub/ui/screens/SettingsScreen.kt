package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack

@Composable
fun SettingsScreen(
    viewModel: GameHubViewModel,
    backStack: NavBackStack<MainRoute>,
    dbConfigs: List<Pair<String, String>>,
    datastoreNames: List<String>,
    modifier: Modifier = Modifier
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconNavigation(backStack) }) }) { padding ->
        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(text = "Backup & Restore", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    BackupButtons(dbConfigs = dbConfigs, datastoreNames = datastoreNames)
                }
            }
            item { HorizontalDivider() }
            item {
                Text(text = "Data", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(text = "Clear hub cache — removes all cached game data. Games will re-sync on next launch.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { showClearConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear cache") }
                }
            }
            item { HorizontalDivider() }
            item {
                Text(text = "About GameHub", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(text = "GameHub aggregates achievements, playtime, and activity across all registered games. Games remain source of truth — hub keeps a mirrored cache pushed via SDK. No leaderboards for now.", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Package: com.vayunmathur.games.hub", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("Clear cache?") },
                text = { Text("This will delete all cached games, achievements, sessions, and activity. Games will re-register on next launch.") },
                confirmButton = { Button(onClick = { viewModel.clearAllData(); showClearConfirm = false }) { Text("Clear") } },
                dismissButton = { com.vayunmathur.library.ui.TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } }
            )
        }
    }
}
