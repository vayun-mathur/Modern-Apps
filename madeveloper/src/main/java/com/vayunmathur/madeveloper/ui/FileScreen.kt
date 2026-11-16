package com.vayunmathur.madeveloper.ui

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
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.platform.MaDevViewModel

/** One source file with line numbers. */
@Composable
fun FileScreen(repoName: String, path: String, viewModel: MaDevViewModel, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(repoName, path) { text = viewModel.file(repoName, path) }
    AppScaffold(title = path.substringAfterLast('/'), onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(text.lines().mapIndexed { index, line -> (index + 1).toString().padStart(4) + "  " + line }.joinToString("\n"))
        }
    }
}
