package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconMerge
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.madeveloper.domain.GitPr
import com.vayunmathur.madeveloper.platform.MaDevUiState

/**
 * Open pull requests across the hosted repositories, from `/prs.json`.
 * Rows open the PR's repository: hosted PRs are reviewed there, and the
 * listing carries no per-PR page of its own.
 */
@Composable
fun PullRequestsScreen(state: MaDevUiState, onOpenPr: (GitPr) -> Unit, onBack: () -> Unit) {
    AppScaffold(
        title = "Pull requests",
        subtitle = if (state.prs.isEmpty()) null else "${state.prs.size} open",
        onNavigateBack = onBack,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.prs.isEmpty()) {
                item {
                    EmptyState(
                        title = "No open pull requests loaded yet.",
                        message = "Pull to refresh from Home once the git host is reachable.",
                    )
                }
            } else {
                item {
                    DashboardSection(
                        title = "Open pull requests",
                        leadingIcon = { modifier, tint -> IconMerge(modifier, tint) },
                    ) {
                        state.prs.forEachIndexed { index, pr ->
                            if (index > 0) DashboardSectionDivider()
                            ListItem(
                                headlineContent = { Text(pr.title, maxLines = 2) },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            append(pr.hostRepo)
                                            if (pr.number > 0) append(" #${pr.number}")
                                            if (pr.author.isNotBlank()) append(" · ${pr.author}")
                                        },
                                    )
                                },
                                leadingContent = { IconMerge() },
                                modifier = Modifier.clickable { onOpenPr(pr) },
                            )
                        }
                    }
                }
            }
        }
    }
}
