package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SelectDropdown
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.domain.TreeEntry
import com.vayunmathur.madeveloper.platform.MaDevViewModel

/** One repository: branch switching, recent commits and its file tree. */
@Composable
fun RepoScreen(
    repoName: String,
    viewModel: MaDevViewModel,
    onOpenFile: (String, String) -> Unit,
    onOpenCommits: (String) -> Unit,
    onOpenCommit: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    val browser = viewModel.browser(repoName)
    val repo = viewModel.repoByName(repoName)
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<TreeEntry>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var treeError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repoName) { viewModel.loadRepo(repoName) }
    LaunchedEffect(repoName, browser?.branch, browser?.path) {
        val branch = browser?.branch ?: return@LaunchedEffect
        treeError = null
        entries = runCatching { viewModel.tree(repoName, branch, browser.path) }
            .getOrElse { treeError = it.message; emptyList() }
    }
    AppScaffold(title = repoName, onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Clone: " + (repo?.cloneUrl ?: "https://git.maproj.org/$repoName.git")) }
            state.repoError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (browser == null) {
                item { Text("Loading repository…") }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Branch:", style = MaterialTheme.typography.titleMedium)
                        SelectDropdown(
                            label = browser.branch.ifBlank { "—" },
                            options = browser.branches.map { it.name },
                            selected = browser.branch,
                            itemLabel = { it },
                            onSelect = { viewModel.selectBranch(repoName, it) },
                        )
                    }
                }
                item { Text("Recent commits", style = MaterialTheme.typography.titleMedium) }
                items(browser.commits.take(5)) { commit ->
                    Column {
                        ListItem(
                            headlineContent = { Text(commit.subject.ifBlank { commit.shortSha }) },
                            supportingContent = { Text(commit.shortSha + " · " + commit.author + " · " + commit.time) },
                        )
                        Button(
                            onClick = { onOpenCommit(browser.branch, commit.sha) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("View commit") }
                    }
                }
                item {
                    Button(onClick = { onOpenCommits(browser.branch) }, modifier = Modifier.fillMaxWidth()) {
                        Text("All commits")
                    }
                }
                item { Text("Files", style = MaterialTheme.typography.titleMedium) }
                item {
                    TextField(
                        value = filter, onValueChange = { filter = it },
                        label = { Text("Filter files") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (browser.path.isNotEmpty()) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.navigateTree(repoName, "") }) { Text("Root") }
                            for ((label, target) in browser.breadcrumbs) {
                                Button(onClick = { viewModel.navigateTree(repoName, target) }) { Text(label) }
                            }
                            Button(onClick = {
                                viewModel.navigateTree(repoName, browser.path.substringBeforeLast('/', ""))
                            }) { Text("Up") }
                        }
                    }
                }
                treeError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                items(entries.filter { it.name.contains(filter, ignoreCase = true) }) { entry ->
                    Column {
                        ListItem(
                            headlineContent = { Text(entry.name) },
                            supportingContent = {
                                Text(if (entry.isDir) "Folder" else (entry.size?.let { "$it B" } ?: "File"))
                            },
                        )
                        Button(
                            onClick = {
                                if (entry.isDir) viewModel.navigateTree(repoName, entry.path)
                                else onOpenFile(browser.branch, entry.path)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (entry.isDir) "Open folder" else "Read file") }
                    }
                }
                item {
                    Button(
                        onClick = { ExternalIntents.openUrl(context, "https://git.maproj.org/$repoName") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("View on git host") }
                }
            }
        }
    }
}
