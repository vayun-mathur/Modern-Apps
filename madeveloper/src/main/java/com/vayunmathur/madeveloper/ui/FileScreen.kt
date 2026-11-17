package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.IconDescription
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.domain.DevPresentation
import com.vayunmathur.madeveloper.domain.GitFile
import com.vayunmathur.madeveloper.platform.MaDevViewModel

/** One source file with line numbers; binary and truncated files say so. */
@Composable
fun FileScreen(repoName: String, path: String, branch: String, viewModel: MaDevViewModel, onBack: () -> Unit) {
    var file by remember { mutableStateOf<GitFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    LaunchedEffect(repoName, path, branch, reloadTick) {
        error = null
        file = runCatching { viewModel.file(repoName, branch, path) }
            .getOrElse { error = it.message; null }
    }
    val fileName = path.substringAfterLast('/')
    AppScaffold(
        title = fileName,
        subtitle = "$repoName · $branch",
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
            error?.let {
                ErrorState(
                    title = "Could not load this file",
                    message = it,
                    retryLabel = "Retry",
                    onRetry = { reloadTick++ },
                )
            }
            val loaded = file
            when {
                loaded == null && error == null -> LoadingState(message = "Loading file…", modifier = Modifier.fillMaxWidth().height(160.dp))
                loaded == null -> Unit
                loaded.isBinary -> DashboardSection(
                    title = "Binary file",
                    leadingIcon = { modifier, tint -> IconDescription(modifier, tint) },
                ) {
                    Text(
                        "Binary file · ${loaded.size} B · ${DevPresentation.shortSha(loaded.sha)}",
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }

                else -> {
                    if (loaded.truncated) {
                        Text(
                            "File truncated by the server · ${loaded.size} B",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                (loaded.content ?: "").lines()
                                    .mapIndexed { index, line ->
                                        (index + 1).toString().padStart(LINE_NUMBER_WIDTH) + "  " + line
                                    }
                                    .joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(Spacing.md),
                            )
                        }
                    }
                    if ((loaded.content ?: "").isBlank()) {
                        EmptyState(title = "Empty file", message = "This file has no text content.")
                    }
                }
            }
        }
    }
}

private const val LINE_NUMBER_WIDTH = 4
