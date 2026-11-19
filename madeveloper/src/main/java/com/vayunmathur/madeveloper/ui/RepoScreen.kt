package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconCode
import com.vayunmathur.library.ui.IconDescription
import com.vayunmathur.library.ui.IconFile
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.IconLink
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SelectDropdown
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.madeveloper.domain.DevPresentation
import com.vayunmathur.madeveloper.domain.FileKind
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
    var treeLoading by remember { mutableStateOf(false) }
    LaunchedEffect(repoName) { viewModel.loadRepo(repoName) }
    LaunchedEffect(repoName, browser?.branch, browser?.path) {
        val branch = browser?.branch ?: return@LaunchedEffect
        treeError = null
        treeLoading = true
        entries = runCatching { viewModel.tree(repoName, branch, browser.path) }
            .getOrElse { treeError = it.message; emptyList() }
        treeLoading = false
    }
    AppScaffold(
        title = repoName,
        subtitle = repo?.defaultBranch?.takeIf { it.isNotBlank() }?.let { "Default branch $it" },
        onNavigateBack = onBack,
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            state.repoError?.let { error ->
                item {
                    ErrorState(
                        title = "Could not load this repository",
                        message = error,
                        retryLabel = "Retry",
                        onRetry = { viewModel.loadRepo(repoName) },
                    )
                }
            }
            if (browser == null) {
                if (state.repoError == null) {
                    item { LoadingState(message = "Loading repository…", modifier = Modifier.fillMaxWidth().height(160.dp)) }
                }
            } else {
                item {
                    DashboardSection(
                        title = "Repository",
                        leadingIcon = { modifier, tint -> IconCode(modifier, tint) },
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    repo?.cloneUrl ?: "https://git.maproj.org/$repoName.git",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            supportingContent = { Text("Clone URL") },
                            leadingContent = { IconLink() },
                        )
                        DashboardSectionDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Text("Branch", style = MaterialTheme.typography.titleSmall)
                            SelectDropdown(
                                label = browser.branch.ifBlank { "—" },
                                options = browser.branches.map { it.name },
                                selected = browser.branch,
                                itemLabel = { it },
                                onSelect = { viewModel.selectBranch(repoName, it) },
                            )
                        }
                    }
                }
                item {
                    DashboardSection(
                        title = "Recent commits",
                        actionLabel = "All commits",
                        onAction = { onOpenCommits(browser.branch) },
                        leadingIcon = { modifier, tint -> IconHistory(modifier, tint) },
                    ) {
                        if (browser.commits.isEmpty()) {
                            Text(
                                "No commits",
                                modifier = Modifier.padding(Spacing.lg),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        browser.commits.take(RECENT_COMMITS).forEachIndexed { index, commit ->
                            if (index > 0) DashboardSectionDivider()
                            CommitRow(
                                commit = commit,
                                onClick = { onOpenCommit(browser.branch, commit.sha) },
                            )
                        }
                    }
                }
                item {
                    DashboardSection(
                        title = "Files",
                        leadingIcon = { modifier, tint -> IconFolder(modifier, tint) },
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            TextField(
                                value = filter, onValueChange = { filter = it },
                                label = { Text("Filter files") }, modifier = Modifier.fillMaxWidth(),
                            )
                            BreadcrumbRow(
                                path = browser.path,
                                breadcrumbs = browser.breadcrumbs,
                                onNavigate = { viewModel.navigateTree(repoName, it) },
                                onUp = {
                                    viewModel.navigateTree(
                                        repoName,
                                        browser.path.substringBeforeLast('/', ""),
                                    )
                                },
                            )
                            treeError?.let { error ->
                                Text(error, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (treeLoading) {
                    item {
                        LoadingState(
                            message = "Loading files…",
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                        )
                    }
                } else {
                    val visible = entries.filter { it.name.contains(filter, ignoreCase = true) }
                    if (visible.isEmpty()) {
                        item {
                            Text(
                                if (filter.isBlank()) "No files" else "No files match \"$filter\".",
                                modifier = Modifier.padding(horizontal = Spacing.lg),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(visible) { entry ->
                        TreeEntryRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDir) {
                                    viewModel.navigateTree(repoName, entry.path)
                                } else {
                                    onOpenFile(browser.branch, entry.path)
                                }
                            },
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            ExternalIntents.openUrl(context, "https://git.maproj.org/$repoName")
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    ) {
                        IconGlobe()
                        Text("View on git host", modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
            }
        }
    }
}

private const val RECENT_COMMITS = 5

@Composable
private fun BreadcrumbRow(
    path: String,
    breadcrumbs: List<Pair<String, String>>,
    onNavigate: (String) -> Unit,
    onUp: () -> Unit,
) {
    if (path.isEmpty()) {
        Text(
            "Repository root",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextButton(onClick = { onNavigate("") }) { Text("Root") }
        for ((label, target) in breadcrumbs) {
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onNavigate(target) }) { Text(label) }
        }
        TextButton(onClick = onUp) { Text("Up") }
    }
}

@Composable
private fun TreeEntryRow(entry: TreeEntry, onClick: () -> Unit) {
    val kind = DevPresentation.fileKindFor(entry.name, entry.isDir)
    ListItem(
        headlineContent = { Text(entry.name) },
        supportingContent = {
            Text(
                when {
                    entry.isDir -> "Folder"
                    entry.size != null -> "${entry.size} B"
                    else -> "File"
                },
            )
        },
        leadingContent = {
            when (kind) {
                FileKind.Folder -> IconFolder()
                FileKind.Code -> IconCode()
                FileKind.Markup, FileKind.Text -> IconDescription()
                FileKind.Image -> IconImage()
                FileKind.Archive, FileKind.Data -> IconFile()
                FileKind.Other -> IconFile()
            }
        },
        trailingContent = { IconChevronRight() },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
