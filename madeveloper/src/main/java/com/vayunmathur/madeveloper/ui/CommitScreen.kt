package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.madeveloper.domain.DevPresentation
import com.vayunmathur.madeveloper.platform.MaDevViewModel

/**
 * One commit's metadata. The JSON API has no diff endpoint, so this shows
 * subject, body, author and time, and links out to the git host instead of
 * inventing a diff view.
 */
@Composable
fun CommitScreen(repoName: String, sha: String, viewModel: MaDevViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(repoName) { viewModel.loadRepo(repoName) }
    // The commit is identified by the route's sha alone; no VM selection state.
    val commit = viewModel.browser(repoName)?.commits?.firstOrNull { it.sha == sha }
    val now = remember { System.currentTimeMillis() }
    AppScaffold(
        title = commit?.shortSha?.ifBlank { sha.take(SHORT_SHA_LENGTH) } ?: sha.take(SHORT_SHA_LENGTH),
        subtitle = repoName,
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (commit == null) {
                EmptyState(
                    title = "Commit not loaded",
                    message = "Commit not in the loaded pages. Open the commits list and load more, or view it on the git host.",
                )
            } else {
                DashboardSection(
                    title = "Commit",
                    leadingIcon = { modifier, tint -> IconHistory(modifier, tint) },
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            commit.subject.ifBlank { commit.shortSha },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            commit.sha,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DashboardSectionDivider()
                    ListItem(
                        headlineContent = { Text(commit.author.ifBlank { "Unknown author" }) },
                        supportingContent = { Text("Author") },
                        leadingContent = { IconPerson() },
                    )
                    DashboardSectionDivider()
                    ListItem(
                        headlineContent = {
                            val relative = DevPresentation.relativeTimeLabel(commit.time, now)
                            Text(relative.ifBlank { commit.time }.ifBlank { "Unknown time" })
                        },
                        supportingContent = { Text(commit.time) },
                        leadingContent = { IconSchedule() },
                    )
                    if (commit.body.isNotBlank()) {
                        DashboardSectionDivider()
                        Text(
                            commit.body,
                            modifier = Modifier.padding(Spacing.lg),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Button(
                onClick = { ExternalIntents.openUrl(context, "https://git.maproj.org/$repoName") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            ) {
                IconGlobe()
                Text("View on git host", modifier = Modifier.padding(start = Spacing.sm))
            }
        }
    }
}

private const val SHORT_SHA_LENGTH = 8
