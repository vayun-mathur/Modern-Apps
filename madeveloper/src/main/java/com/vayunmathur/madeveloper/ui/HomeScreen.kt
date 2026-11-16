package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconCode
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.platform.MaDevUiState

/** Home: push status, CI summary and the hosted repositories. */
@Composable
fun HomeScreen(state: MaDevUiState, onRefresh: () -> Unit, onOpenRepo: (String) -> Unit, onOpenTests: () -> Unit, onOpenCatalog: () -> Unit, onOpenPush: () -> Unit) {
    AppScaffold(title = "MA Developer", scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.connected) "Push socket connected" else "Push socket offline", style = MaterialTheme.typography.titleMedium)
                        Text(if (state.paired) "This device is paired for pushed installs." else "Pairing code: " + state.pairingCode)
                        Button(onClick = onOpenPush) { Text("Pushed installs") }
                    }
                }
            }
            state.tests?.latest?.let { latest ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("CI: " + latest.status, style = MaterialTheme.typography.titleMedium)
                            Text(latest.subject)
                            state.tests.coveragePct?.let { Text("Coverage %.1f%%".format(it)) }
                            Button(onClick = onOpenTests) { Text("Test history") }
                        }
                    }
                }
            }
            item { Button(onClick = onOpenCatalog, modifier = Modifier.fillMaxWidth()) { Text("Available builds (read-only catalog)") } }
            item { Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh") } }
            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            items(state.repos) { repo ->
                ListItem(
                    headlineContent = { Text(repo.name) },
                    supportingContent = {
                        Text(
                            listOf(repo.defaultBranch, repo.shortHeadSha, repo.headSubject)
                                .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { repo.cloneUrl },
                        )
                    },
                    leadingContent = { IconCode() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onOpenRepo(repo.name) }, modifier = Modifier.fillMaxWidth()) { Text("Open " + repo.name) }
            }
        }
    }
}
