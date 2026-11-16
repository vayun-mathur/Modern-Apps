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

/** Read-only catalog of available builds; installs arrive by server push. */
@Composable
fun CatalogScreen(state: MaDevUiState, onBack: () -> Unit) {
    AppScaffold(title = "Available builds", onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Read-only: installs are pushed from the server and confirmed here.") }
            items(state.catalog) { app ->
                ListItem(
                    headlineContent = { Text(app.name) },
                    supportingContent = { Text(app.packageName + " · " + (app.versionName ?: "") + " (" + app.versionCode + ") · " + app.sizeLabel) },
                )
            }
            if (state.catalog.isEmpty()) item { Text("No catalog loaded yet.") }
        }
    }
}
