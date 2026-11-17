package com.vayunmathur.code.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.platform.CodeUiState
import com.vayunmathur.code.platform.TreeRowUiState
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconFile
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.IconFolderOpen
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconNewFile
import com.vayunmathur.library.ui.IconNewFolder
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/** The file-management dialog the tree pane is currently showing, if any. UI-local state. */
private sealed interface TreeDialog {
    data class NewFile(val parentIndex: Int?) : TreeDialog
    data class NewFolder(val parentIndex: Int?) : TreeDialog
    data class Rename(val index: Int, val current: String) : TreeDialog
    data class Delete(val index: Int, val name: String) : TreeDialog
}

/**
 * The drawer's file browser: a header with folder/create actions and a lazily-built, flat file
 * tree. Tapping a directory expands/collapses it; tapping a file opens it (and the caller closes
 * the drawer). Each row carries an overflow menu for create/rename/delete. File-management
 * dialog state is kept local here so the ViewModel stays focused on the model.
 */
@Composable
fun FileTreePane(
    state: CodeUiState,
    actions: CodeActions,
    onOpenFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onFileOpened: () -> Unit,
) {
    var dialog by remember { mutableStateOf<TreeDialog?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.rootName ?: "Explorer",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.folderOpen) {
                IconButton(onClick = { dialog = TreeDialog.NewFile(null) }) { IconNewFile() }
                IconButton(onClick = { dialog = TreeDialog.NewFolder(null) }) { IconNewFolder() }
            }
            IconButton(onClick = onOpenFolder) { IconFolderOpen() }
        }
        HorizontalDivider()

        if (!state.folderOpen) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconFolder(Modifier.size(48.dp))
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.no_folder_opened),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Button(onClick = onOpenFolder) { Text(stringResource(R.string.open_folder)) }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onOpenFile) { Text(stringResource(R.string.open_file)) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                // Positional keys: the flat tree can hold two files with the same name at the
                // same depth, and the rows themselves carry no state worth preserving.
                itemsIndexed(state.nodes) { index, node ->
                    TreeRow(
                        node = node,
                        onClick = {
                            val wasFile = !node.isDirectory
                            actions.toggleNode(index)
                            if (wasFile) onFileOpened()
                        },
                        onOpenDialog = { dialog = it },
                        index = index,
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        is TreeDialog.NewFile -> NameDialog(
            title = stringResource(R.string.new_file),
            confirmLabel = stringResource(UiR.string.create),
            onConfirm = { actions.createFile(d.parentIndex, it) },
            onDismiss = { dialog = null },
        )
        is TreeDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.new_folder),
            confirmLabel = stringResource(UiR.string.create),
            onConfirm = { actions.createFolder(d.parentIndex, it) },
            onDismiss = { dialog = null },
        )
        is TreeDialog.Rename -> NameDialog(
            title = stringResource(UiR.string.rename),
            confirmLabel = stringResource(UiR.string.rename),
            initial = d.current,
            onConfirm = { actions.renameNode(d.index, it) },
            onDismiss = { dialog = null },
        )
        is TreeDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.delete_title, d.name),
            message = stringResource(R.string.delete_message),
            confirmLabel = stringResource(UiR.string.delete),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = { actions.deleteNode(d.index) },
            onDismiss = { dialog = null },
        )
        null -> {}
    }
}

@Composable
private fun TreeRow(
    node: TreeRowUiState,
    onClick: () -> Unit,
    onOpenDialog: (TreeDialog) -> Unit,
    index: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (node.depth * 16 + 8).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isDirectory) {
            if (node.expanded) IconKeyboardArrowDown(Modifier.size(20.dp)) else IconChevronRight(Modifier.size(20.dp))
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Spacer(Modifier.width(4.dp))
        when {
            node.isDirectory && node.expanded -> IconFolderOpen(Modifier.size(20.dp))
            node.isDirectory -> IconFolder(Modifier.size(20.dp))
            else -> IconFile(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = node.name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OverflowMenu(icon = { IconMoreVert() }) {
            if (node.isDirectory) {
                Item(text = stringResource(R.string.new_file)) {
                    onOpenDialog(TreeDialog.NewFile(index))
                }
                Item(text = stringResource(R.string.new_folder)) {
                    onOpenDialog(TreeDialog.NewFolder(index))
                }
            }
            Item(
                text = stringResource(UiR.string.rename),
                leadingIcon = { IconEdit() },
            ) { onOpenDialog(TreeDialog.Rename(index, node.name)) }
            Item(
                text = stringResource(UiR.string.delete),
                leadingIcon = { IconDelete() },
            ) { onOpenDialog(TreeDialog.Delete(index, node.name)) }
        }
    }
}

/** A single-field name-entry dialog used for create-file/create-folder/rename. */
@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(UiR.string.name)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()); onDismiss() },
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}
