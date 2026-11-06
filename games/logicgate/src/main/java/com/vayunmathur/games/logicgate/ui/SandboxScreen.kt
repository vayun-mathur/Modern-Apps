package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.platform.ComponentCard
import com.vayunmathur.games.logicgate.platform.SandboxUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExtendedFloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.GameTopBarActions

/** The player's components under their group names, with a button to start a new one. */
@Composable
fun SandboxScreen(
    state: SandboxUiState,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onOpenGameCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppScaffold(
        title = stringResource(R.string.lg_sandbox_title),
        modifier = modifier,
        actions = { GameTopBarActions(onOpenGameCenter = onOpenGameCenter) },
        scrollBehavior = appBarScrollBehavior(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.lg_new_component)) },
                icon = { IconAdd() },
                onClick = onCreate,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = padding.calculateTopPadding() + Spacing.sm,
                bottom = padding.calculateBottomPadding() + FAB_CLEARANCE,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "header") {
                Text(stringResource(R.string.lg_sandbox_tagline), style = MaterialTheme.typography.titleMedium)
            }
            if (state.components.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.lg_sandbox_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.lg),
                    )
                }
            }
            for ((group, cards) in state.groups) {
                item(key = "group:$group") {
                    Text(
                        group,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.lg),
                    )
                }
                items(cards, key = { it.id }) { card -> ComponentRow(card, onOpen) }
            }
        }
    }
}

@Composable
private fun ComponentRow(card: ComponentCard, onOpen: (String) -> Unit) {
    Card(onClick = { onOpen(card.id) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text(card.name, style = MaterialTheme.typography.titleMedium)
            Text(
                card.cost?.let { stringResource(R.string.lg_component_summary, card.inputs, card.outputs, it) }
                    ?: stringResource(R.string.lg_component_broken, card.inputs, card.outputs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val FAB_CLEARANCE = Spacing.xl * 4
