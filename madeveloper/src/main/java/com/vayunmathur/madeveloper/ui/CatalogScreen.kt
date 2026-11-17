package com.vayunmathur.madeveloper.ui

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
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.platform.MaDevUiState

/** Read-only catalog of available builds; installs arrive by server push. */
@Composable
fun CatalogScreen(state: MaDevUiState, onBack: () -> Unit) {
    AppScaffold(
        title = "Available builds",
        subtitle = "Read-only catalog",
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                DashboardSection(
                    title = "About this catalog",
                    leadingIcon = { modifier, tint -> IconInfo(modifier, tint) },
                ) {
                    Text(
                        "Read-only: installs are pushed from the server and confirmed here.",
                        modifier = Modifier.padding(Spacing.lg),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                DashboardSection(
                    title = "Builds",
                    leadingIcon = { modifier, tint -> IconApps(modifier, tint) },
                ) {
                    if (state.catalog.isEmpty()) {
                        Text(
                            "No catalog loaded yet.",
                            modifier = Modifier.padding(Spacing.lg),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.catalog.forEachIndexed { index, app ->
                        if (index > 0) DashboardSectionDivider()
                        ListItem(
                            headlineContent = { Text(app.name) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    if (app.summary.isNotBlank()) Text(app.summary, maxLines = 2)
                                    Text(
                                        app.packageName + " · " + (app.versionName ?: "") +
                                            " (" + app.versionCode + ") · " + app.sizeLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingContent = { IconApps() },
                        )
                    }
                }
            }
        }
    }
}
