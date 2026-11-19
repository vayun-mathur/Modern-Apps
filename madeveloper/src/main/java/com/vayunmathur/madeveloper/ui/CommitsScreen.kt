package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
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
    AppScaffold(
        title = "Commits",
        subtitle = "$repoName · $branch",
        onNavigateBack = onBack,
    ) { padding ->
        AppPullToRefreshBox(
            isRefreshing = false,
            onRefresh = { viewModel.loadCommits(repoName, loadMore = false) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                viewModel.state.repoError?.let { error ->
                    item {
                        ErrorState(
                            title = "Could not load commits",
                            message = error,
                            retryLabel = "Retry",
                            onRetry = { viewModel.loadCommits(repoName, loadMore = false) },
                        )
                    }
                }
                if (browser == null) {
                    if (viewModel.state.repoError == null) {
                        item { LoadingState(message = "Loading commits…", modifier = Modifier.fillMaxWidth().height(160.dp)) }
                    }
                } else {
                    item {
                        DashboardSection(title = "Commits on $branch") {
                            if (browser.commits.isEmpty()) {
                                Text(
                                    "No commits",
                                    modifier = Modifier.padding(Spacing.lg),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(browser.commits) { commit ->
                        CommitRow(commit = commit, onClick = { onOpenCommit(commit.sha) })
                    }
                    if (!browser.commitsExhausted && browser.commits.isNotEmpty()) {
                        item {
                            Button(
                                onClick = { viewModel.loadCommits(repoName, loadMore = true) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                            ) { Text("Load more") }
                        }
                    }
                }
            }
        }
    }
}
