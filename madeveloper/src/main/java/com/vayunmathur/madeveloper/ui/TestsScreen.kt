package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.madeveloper.domain.DevPresentation
import com.vayunmathur.madeveloper.domain.TestRun
import com.vayunmathur.madeveloper.platform.MaDevUiState

/** CI history from the git host tests page. */
@Composable
fun TestsScreen(state: MaDevUiState, onBack: () -> Unit) {
    AppScaffold(
        title = "CI tests",
        subtitle = state.tests?.coveragePct?.let { "Coverage %.1f%%".format(it) },
        onNavigateBack = onBack,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.tests == null) {
                item {
                    EmptyState(
                        title = "No CI data loaded yet.",
                        message = "Pull to refresh from Home once the git host is reachable.",
                    )
                }
            } else {
                state.tests.running?.let { running ->
                    item {
                        DashboardSection(
                            title = "Running now",
                            leadingIcon = { modifier, tint -> IconSchedule(modifier, tint) },
                        ) {
                            TestRunRow(running)
                        }
                    }
                }
                state.tests.latest?.let { latest ->
                    item {
                        DashboardSection(
                            title = "Latest",
                            leadingIcon = { modifier, tint -> IconCheckCircle(modifier, tint) },
                        ) {
                            TestRunRow(latest)
                        }
                    }
                }
                item {
                    DashboardSection(
                        title = "History",
                        leadingIcon = { modifier, tint -> IconHistory(modifier, tint) },
                    ) {
                        if (state.tests.history.isEmpty()) {
                            Text(
                                "No previous runs",
                                modifier = Modifier.padding(Spacing.lg),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.tests.history.forEachIndexed { index, run ->
                            if (index > 0) DashboardSectionDivider()
                            TestRunRow(run)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestRunRow(run: TestRun) {
    val status = DevPresentation.ciStatusOf(run.status)
    ListItem(
        headlineContent = { Text(run.subject.ifBlank { "CI run" }, maxLines = 2) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    buildString {
                        append(run.sha.take(SHORT_SHA_LENGTH))
                        if (run.tests > 0) append(" · ${run.tests} tests")
                        if (run.failed > 0) append(" · failed ${run.failed}")
                        run.durationSecs?.let { append(" · ${it}s") }
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = { StatusChip(run.status, status) },
    )
}

private const val SHORT_SHA_LENGTH = 8
