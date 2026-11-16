package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.domain.TreeEntry
import com.vayunmathur.madeveloper.platform.MaDevViewModel

/** One repository: refs, issues, pull requests and its file tree. */
@Composable
fun RepoScreen(repoName: String, viewModel: MaDevViewModel, onOpenFile: (String) -> Unit, onBack: () -> Unit) {
    val repo = viewModel.repoByName(repoName)
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<TreeEntry>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(repoName, path) { if (repo != null) entries = viewModel.tree(repo, path) }
    AppScaffold(title = repoName, onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Clone: " + (repo?.cloneUrl ?: "")) }
            items(repo?.refs.orEmpty().take(12)) { ref -> ListItem(headlineContent = { Text(ref.name) }, supportingContent = { Text(ref.shortSha) }) }
            item { TextField(value = filter, onValueChange = { filter = it }, label = { Text("Filter files") }, modifier = Modifier.fillMaxWidth()) }
            if (path.isNotEmpty()) item { Button(onClick = { path = path.substringBeforeLast('/', "") }) { Text("Up") } }
            items(entries.filter { it.name.contains(filter, ignoreCase = true) }) { entry ->
                ListItem(headlineContent = { Text(entry.name) }, supportingContent = { Text(if (entry.isDir) "Folder" else "File") })
                Button(onClick = { if (entry.isDir) path = entry.path else onOpenFile(entry.path) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (entry.isDir) "Open folder" else "Read file")
                }
            }
            item { Text("Issues and pull requests are on the Home data set; source files come from the host mirror.") }
        }
    }
}
