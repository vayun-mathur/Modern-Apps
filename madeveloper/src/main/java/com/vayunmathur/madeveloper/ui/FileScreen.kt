package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.domain.GitFile
import com.vayunmathur.madeveloper.platform.MaDevViewModel

/** One source file with line numbers; binary and truncated files say so. */
@Composable
fun FileScreen(repoName: String, path: String, branch: String, viewModel: MaDevViewModel, onBack: () -> Unit) {
    var file by remember { mutableStateOf<GitFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repoName, path, branch) {
        error = null
        file = runCatching { viewModel.file(repoName, branch, path) }
            .getOrElse { error = it.message; null }
    }
    AppScaffold(title = path.substringAfterLast('/'), onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val loaded = file
            when {
                loaded == null && error == null -> Text("Loading file…")
                loaded == null -> Unit
                loaded.isBinary -> Text("Binary file · ${loaded.size} B · ${loaded.sha.take(8)}")
                else -> {
                    if (loaded.truncated) Text("File truncated by the server · ${loaded.size} B")
                    Text(
                        (loaded.content ?: "").lines()
                            .mapIndexed { index, line -> (index + 1).toString().padStart(4) + "  " + line }
                            .joinToString("\n"),
                    )
                }
            }
        }
    }
}
