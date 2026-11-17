package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.IconBugReport
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconCode
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconInstall
import com.vayunmathur.library.ui.IconMerge
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.domain.CiStatus
import com.vayunmathur.madeveloper.domain.DevPresentation
import com.vayunmathur.madeveloper.domain.GitRepo
import com.vayunmathur.madeveloper.platform.MaDevUiState

/** Home dashboard: push status, CI summary, quick links and hosted repositories. */
@Composable
fun HomeScreen(
    state: MaDevUiState,
    onRefresh: () -> Unit,
    onOpenRepo: (String) -> Unit,
    onOpenTests: () -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenPush: () -> Unit,
) {
    AppScaffold(
        title = "MA Developer",
        scrollBehavior = appBarScrollBehavior(),
        actions = {
            IconButton(onClick = onRefresh) { IconRefresh() }
        },
    ) { padding ->
        AppPullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item { PushStatusSection(state, onOpenPush) }
                state.tests?.latest?.let { latest ->
                    item {
                        DashboardSection(
                            title = "Continuous integration",
                            leadingIcon = { modifier, tint -> IconCheckCircle(modifier, tint) },
                        ) {
                            ListItem(
                                headlineContent = { Text(latest.subject.ifBlank { "Latest run" }) },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                        Text(
                                            DevPresentation.shortSha(latest.sha),
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        state.tests.coveragePct?.let {
                                            Text("Coverage %.1f%%".format(it))
                                        }
                                    }
                                },
                                trailingContent = {
                                    StatusChip(latest.status, DevPresentation.ciStatusOf(latest.status))
                                },
                                modifier = Modifier.clickable(onClick = onOpenTests),
                            )
                        }
                    }
                }
                item { QuickLinksSection(state, onOpenTests, onOpenCatalog, onOpenPush) }
                if (state.loading && state.repos.isEmpty()) {
                    item { LoadingState(message = "Loading repositories…", modifier = Modifier.fillMaxWidth().height(160.dp)) }
                }
                state.error?.let { error ->
                    if (state.repos.isEmpty()) {
                        item {
                            ErrorState(
                                title = "Could not load the git host",
                                message = error,
                                retryLabel = "Retry",
                                onRetry = onRefresh,
                            )
                        }
                    }
                }
                item {
                    DashboardSection(
                        title = "Repositories",
                        leadingIcon = { modifier, tint -> IconCode(modifier, tint) },
                    ) {
                        if (state.repos.isEmpty() && !state.loading) {
                            Text(
                                "No repositories",
                                modifier = Modifier.padding(Spacing.lg),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.repos.forEachIndexed { index, repo ->
                            if (index > 0) DashboardSectionDivider()
                            RepoRow(repo = repo, onClick = { onOpenRepo(repo.name) })
                        }
                    }
                }
                if (state.issues.isNotEmpty() || state.prs.isNotEmpty()) {
                    item { ActivitySection(state) }
                }
            }
        }
    }
}

@Composable
private fun PushStatusSection(state: MaDevUiState, onOpenPush: () -> Unit) {
    DashboardSection(
        title = "Pushed installs",
        actionLabel = "Open",
        onAction = onOpenPush,
        leadingIcon = { modifier, tint -> IconInstall(modifier, tint) },
    ) {
        ListItem(
            headlineContent = {
                Text(if (state.connected) "Push socket connected" else "Push socket offline")
            },
            supportingContent = {
                Text(
                    if (state.paired) {
                        "This device is paired for pushed installs."
                    } else {
                        "Pairing code: " + state.pairingCode
                    },
                )
            },
            leadingContent = { IconInstall() },
            trailingContent = {
                StatusChip(
                    label = if (state.connected) "Connected" else "Offline",
                    status = if (state.connected) CiStatus.Passed else CiStatus.Unknown,
                )
            },
            modifier = Modifier.clickable(onClick = onOpenPush),
        )
    }
}

@Composable
private fun QuickLinksSection(
    state: MaDevUiState,
    onOpenTests: () -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenPush: () -> Unit,
) {
    DashboardSection(
        title = "Quick links",
        leadingIcon = { modifier, tint -> IconApps(modifier, tint) },
    ) {
        QuickLinkRow("Test history", "CI runs and coverage", onOpenTests) { IconHistory() }
        DashboardSectionDivider()
        QuickLinkRow("Available builds", "Read-only catalog", onOpenCatalog) { IconApps() }
        DashboardSectionDivider()
        QuickLinkRow("Pushed installs", "Pairing and pending installs", onOpenPush) { IconInstall() }
        if (state.issues.isNotEmpty()) {
            DashboardSectionDivider()
            QuickLinkRow("Issues", "${state.issues.size} open across hosted repos", {}) { IconBugReport() }
        }
        if (state.prs.isNotEmpty()) {
            DashboardSectionDivider()
            QuickLinkRow("Pull requests", "${state.prs.size} open across hosted repos", {}) { IconMerge() }
        }
    }
}

@Composable
private fun QuickLinkRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = icon,
        trailingContent = { IconChevronRight() },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ActivitySection(state: MaDevUiState) {
    DashboardSection(title = "Recent activity") {
        state.issues.take(3).forEach { issue ->
            ListItem(
                headlineContent = { Text(issue.title, maxLines = 1) },
                supportingContent = {
                    Text("${issue.repo} #${issue.number} · ${issue.author}")
                },
                leadingContent = { IconBugReport() },
            )
        }
        state.prs.take(3).forEach { pr ->
            ListItem(
                headlineContent = { Text(pr.title, maxLines = 1) },
                supportingContent = { Text("${pr.repo} #${pr.number} · ${pr.author}") },
                leadingContent = { IconMerge() },
            )
        }
    }
}

@Composable
private fun RepoRow(repo: GitRepo, onClick: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val relative = DevPresentation.relativeTimeLabel(repo.headTime, now)
    ListItem(
        headlineContent = { Text(repo.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (repo.headSubject.isNotBlank()) Text(repo.headSubject, maxLines = 1)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        DevPresentation.repoLanguage(repo.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (repo.defaultBranch.isNotBlank()) {
                        Text(
                            repo.defaultBranch,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (repo.shortHeadSha.isNotBlank()) {
                        Text(
                            repo.shortHeadSha,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (relative.isNotBlank()) {
                        Text(
                            relative,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        leadingContent = { IconCode() },
        trailingContent = { IconChevronRight() },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
