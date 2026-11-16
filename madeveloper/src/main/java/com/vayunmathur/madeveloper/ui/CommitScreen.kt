package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
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
    AppScaffold(
        title = commit?.shortSha?.ifBlank { sha.take(8) } ?: sha.take(8),
        onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (commit == null) {
                Text("Commit not in the loaded pages. Open the commits list and load more, or view it on the git host.")
            } else {
                Text(commit.subject.ifBlank { commit.shortSha }, style = MaterialTheme.typography.titleMedium)
                Text("Author: " + commit.author)
                Text("Time: " + commit.time)
                Text("SHA: " + commit.sha)
                if (commit.body.isNotBlank()) Text(commit.body)
            }
            Button(
                onClick = { ExternalIntents.openUrl(context, "https://git.maproj.org/$repoName") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("View on git host") }
        }
    }
}
