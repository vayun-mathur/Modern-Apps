package com.vayunmathur.files.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.TrashUiState
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDeleteForever
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.R as UiR

/**
 * The system-trash browser, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — same split as [DirectoryScreen].
 */
@Composable
fun TrashScreen(
    state: TrashUiState,
    actions: FilesActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenDrawer: () -> Unit = {},
) {
    var showEmptyConfirm by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = { Text(stringResource(R.string.empty_trash)) },
            text = { Text(stringResource(R.string.delete_permanently_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.emptyTrash()
                        showEmptyConfirm = false
                    },
                ) { Text(stringResource(R.string.delete_forever)) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            },
        )
    }

    BackHandler(state.selectedItems.isNotEmpty()) {
        actions.clearTrashSelection()
    }

    AppScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        navigationIcon = {
            if (state.selectedItems.isEmpty()) {
                IconButton(onClick = onOpenDrawer) { IconMenu() }
            }
        },
        title = { Text(stringResource(R.string.trash)) },
        actions = {
            if (state.selectedItems.isNotEmpty()) {
                IconButton(onClick = { actions.clearTrashSelection() }) { IconClose() }
                IconButton(onClick = { actions.restoreTrashSelection() }) { IconRestore() }
                IconButton(onClick = { actions.deleteForeverTrashSelection() }) { IconDeleteForever() }
            } else if (state.items.isNotEmpty()) {
                IconButton(onClick = { showOverflow = true }) { IconMoreVert() }
                DropdownMenu(
                    expanded = showOverflow,
                    onDismissRequest = { showOverflow = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.empty_trash)) },
                        leadingIcon = { IconDeleteForever() },
                        onClick = { showOverflow = false; showEmptyConfirm = true },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.trash_auto_delete_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.items.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.trash_empty),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(state.items, key = { it.key }) { item ->
                        val isSelected = state.selectedItems.any { it.key == item.key }
                        TrashRow(
                            item = item,
                            isSelected = isSelected,
                            onClick = {
                                if (state.selectedItems.isNotEmpty()) actions.toggleTrashSelection(item)
                            },
                            onLongClick = { actions.toggleTrashSelection(item) },
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashRow(
    item: FileBrowserItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        ListItem(
            content = { Text(item.name.ifEmpty { "/" }) },
            leadingContent = { FileLeading(item, isSelected, 40.dp) },
            supportingContent = {
                item.size?.let { size -> Text(Formatter.formatShortFileSize(context, size)) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
