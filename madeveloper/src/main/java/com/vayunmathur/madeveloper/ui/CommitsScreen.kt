package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.platform.MaDevViewModel

/** Paged commit list for one repository branch. */
@Composable
fun CommitsScreen(
    repoName: String,
    branch: String,
    viewModel: MaDevViewModel,
    onOpenCommit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val browser = viewModel.browser(repoName)
    LaunchedEffect(repoName, branch, browser?.branch) {
        viewModel.loadRepo(repoName)
        if (browser != null && browser.commits.isEmpty()) viewModel.loadCommits(repoName, loadMore = false)
    }
    AppScaffold(title = "Commits · $branch", onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            viewModel.state.repoError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            if (browser == null) {
                item { Text("Loading commits…") }
            } else {
                items(browser.commits) { commit ->
                    Column {
                        ListItem(
                            headlineContent = { Text(commit.subject.ifBlank { commit.shortSha }) },
                            supportingContent = {
                                Text(commit.shortSha + " · " + commit.author + " · " + commit.time)
                            },
                        )
                        Button(onClick = { onOpenCommit(commit.sha) }, modifier = Modifier.fillMaxWidth()) {
                            Text("View commit")
                        }
                    }
                }
                if (browser.commits.isEmpty()) item { Text("No commits loaded yet.") }
                if (!browser.commitsExhausted) {
                    item {
                        Button(
                            onClick = { viewModel.loadCommits(repoName, loadMore = true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Load more") }
                    }
                }
            }
        }
    }
}
