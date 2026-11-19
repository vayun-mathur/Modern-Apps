package com.vayunmathur.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.launcher.platform.TvHomeState

/**
 * Edit overlays: the per-tile menu (Move/Replace/Remove/Uninstall-or-App-info) and the
 * add-app picker. Both are dialogs over the grid, in the home screen's own glass look.
 *
 * Removing only takes the tile away: the app stays installed and can be added back (the
 * tv-detox contract).
 */
@Composable
fun TvEditMenu(
    state: TvHomeState,
    onDismiss: () -> Unit,
    onStartMove: (String) -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onUninstallOrInfo: () -> Unit,
    onPickAddCandidate: (String) -> Unit,
    onCloseAddMenu: () -> Unit,
) {
    val editing = state.editingKey
    if (editing != null) {
        val tile = state.rowApps.firstOrNull { it.packageName == editing }
        val uninstallLabel = if (tile?.userInstalled == true) "Uninstall" else "App info"
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = tile?.label ?: editing) },
            text = {
                Column {
                    TvMenuRow(label = "Move", onClick = { onStartMove(editing) })
                    TvMenuRow(label = "Replace", onClick = onReplace)
                    TvMenuRow(label = "Remove from home", onClick = onRemove)
                    TvMenuRow(label = uninstallLabel, onClick = onUninstallOrInfo)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(text = "Cancel") }
            },
        )
    }
    if (state.showAddMenu) {
        val title = if (state.addReplacesIndex >= 0) {
            val current = state.rowApps.getOrNull(state.addReplacesIndex)?.label ?: ""
            if (current.isNotBlank()) "Replace $current" else "Replace app"
        } else {
            "Add an app"
        }
        AlertDialog(
            onDismissRequest = onCloseAddMenu,
            title = { Text(text = title) },
            text = {
                if (state.addCandidates.isEmpty()) {
                    Text(
                        text = "Every app is already here",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xE6FFFFFF),
                    )
                } else {
                    // Plain Column, not a LazyColumn: the dialog's text slot measures with
                    // bounded height, but a lazy container inside it is the same nested-scroll
                    // hazard that crashed the grid — and candidate lists are short.
                    val firstFocus = remember { FocusRequester() }
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        for (candidate in state.addCandidates) {
                            TvMenuRow(
                                label = candidate.label,
                                onClick = { onPickAddCandidate(candidate.packageName) },
                                modifier = if (candidate == state.addCandidates.first()) {
                                    Modifier.focusRequester(firstFocus)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                    LaunchedEffect(Unit) {
                        runCatching { firstFocus.requestFocus() }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onCloseAddMenu) { Text(text = "Cancel") }
            },
        )
    }
}

@Composable
private fun TvMenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = Color(0xE6FFFFFF),
        maxLines = 1,
        modifier = modifier
            .fillMaxWidth()
            .focusable()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        overflow = TextOverflow.Ellipsis,
    )
}

