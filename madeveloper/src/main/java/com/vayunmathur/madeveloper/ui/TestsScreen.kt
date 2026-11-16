package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.platform.MaDevUiState

/** CI history from the git host tests page. */
@Composable
fun TestsScreen(state: MaDevUiState, onBack: () -> Unit) {
    AppScaffold(title = "CI tests", onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tests?.running?.let { item { ListItem(headlineContent = { Text("Running: " + it.subject) }, supportingContent = { Text(it.sha.take(8)) }) } }
            items(state.tests?.history.orEmpty()) { run ->
                ListItem(headlineContent = { Text(run.subject) }, supportingContent = { Text(run.status + " · failed " + run.failed + " · " + run.sha.take(8)) })
            }
            if (state.tests == null) item { Text("No CI data loaded yet.") }
        }
    }
}
