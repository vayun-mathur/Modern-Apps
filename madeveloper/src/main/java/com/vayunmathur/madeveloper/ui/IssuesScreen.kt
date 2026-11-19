package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconBugReport
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.madeveloper.domain.GitIssue
import com.vayunmathur.madeveloper.platform.MaDevUiState

/**
 * Open issues across the hosted repositories, from `/issues.json`.
 * Rows open the issue's GitHub page — that is where these issues live;
 * the git host only mirrors the listing.
 */
@Composable
fun IssuesScreen(state: MaDevUiState, onOpenIssue: (GitIssue) -> Unit, onBack: () -> Unit) {
    AppScaffold(
        title = "Issues",
        subtitle = if (state.issues.isEmpty()) null else "${state.issues.size} open",
        onNavigateBack = onBack,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.issues.isEmpty()) {
                item {
                    EmptyState(
                        title = "No open issues loaded yet.",
                        message = "Pull to refresh from Home once the git host is reachable.",
                    )
                }
            } else {
                item {
                    DashboardSection(
                        title = "Open issues",
                        leadingIcon = { modifier, tint -> IconBugReport(modifier, tint) },
                    ) {
                        state.issues.forEachIndexed { index, issue ->
                            if (index > 0) DashboardSectionDivider()
                            ListItem(
                                headlineContent = { Text(issue.title, maxLines = 2) },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                        Text("${issue.repo} #${issue.number} · ${issue.author}")
                                        if (issue.labels.isNotEmpty()) {
                                            Text(
                                                issue.labels.joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                },
                                trailingContent = {
                                    if (issue.comments > 0) {
                                        Text(
                                            "${issue.comments} comments",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                leadingContent = { IconBugReport() },
                                modifier = Modifier.clickable { onOpenIssue(issue) },
                            )
                        }
                    }
                }
            }
        }
    }
}
