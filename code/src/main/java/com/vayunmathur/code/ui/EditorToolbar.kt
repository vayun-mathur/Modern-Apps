package com.vayunmathur.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.platform.CodeUiState
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconFindReplace
import com.vayunmathur.library.ui.IconFormatIndentIncrease
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconWrapText
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/** Horizontally scrollable strip of open tabs, each with a dirty indicator and close button. */
@Composable
internal fun TabStrip(state: CodeUiState, actions: CodeActions) {
    var pendingCloseIndex by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        state.tabs.forEachIndexed { index, tab ->
            val selected = index == state.currentIndex
            val background =
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            Row(
                modifier = Modifier
                    .background(background)
                    .clickable { actions.selectTab(index) }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                if (tab.isDirty) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                IconButton(
                    onClick = {
                        if (tab.isDirty) pendingCloseIndex = index else actions.closeTab(index)
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    IconClose(Modifier.size(16.dp))
                }
            }
        }
    }

    pendingCloseIndex?.let { index ->
        ConfirmDialog(
            title = stringResource(R.string.discard_changes_title),
            confirmLabel = stringResource(R.string.discard),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = { actions.closeTab(index) },
            onDismiss = { pendingCloseIndex = null },
        )
    }
}

/** Undo/redo, save, find, soft-wrap, tab-insert, an overflow menu and a language indicator. */
@Composable
internal fun EditorToolbar(
    state: CodeUiState,
    actions: CodeActions,
    onToggleFind: () -> Unit,
    onGoToLine: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
    onOpenQuickOpen: () -> Unit = {},
    onOpenPalette: () -> Unit = {},
    onOpenOutline: () -> Unit = {},
    onResolveConflicts: () -> Unit = {},
    onOpenProblems: () -> Unit = {},
) {
    val tab = state.activeTab ?: return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { actions.undo() }, enabled = tab.canUndo) { IconUndo() }
        IconButton(onClick = { actions.redo() }, enabled = tab.canRedo) { IconRedo() }
        IconButton(onClick = { actions.save() }, enabled = tab.isDirty) { IconSave() }
        IconButton(onClick = onToggleFind) { IconFindReplace() }
        IconButton(onClick = { actions.toggleSoftWrap() }) {
            IconWrapText(
                tint = if (state.softWrap) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { actions.insertText(" ".repeat(state.tabWidth)) }) { IconFormatIndentIncrease() }
        OverflowMenu(icon = { IconMoreVert() }) {
            Item(text = stringResource(R.string.command_palette)) { onOpenPalette() }
            Item(text = stringResource(R.string.quick_open)) { onOpenQuickOpen() }
            Item(text = stringResource(R.string.go_to_symbol)) { onOpenOutline() }
            Item(text = stringResource(R.string.go_to_line)) { onGoToLine() }
            Item(text = stringResource(R.string.toggle_comment)) { actions.toggleComment() }
            Item(text = stringResource(R.string.duplicate_line)) { actions.duplicateLine() }
            Item(text = stringResource(R.string.move_line_up)) { actions.moveLineUp() }
            Item(text = stringResource(R.string.move_line_down)) { actions.moveLineDown() }
            Item(text = stringResource(R.string.delete_line)) { actions.deleteLine() }
            Item(text = stringResource(R.string.search_in_project)) { onOpenSearch() }
            Item(text = stringResource(R.string.source_control)) { onOpenGit() }
            Item(text = stringResource(R.string.terminal)) { onOpenTerminal() }
            Item(text = stringResource(R.string.preview)) { onOpenPreview() }
            Item(text = stringResource(R.string.format_document)) { actions.formatDocument() }
            Item(text = stringResource(R.string.resolve_conflicts)) { onResolveConflicts() }
            Item(text = stringResource(R.string.problems)) { onOpenProblems() }
            Item(text = stringResource(R.string.split_view)) { actions.toggleSplit() }
        }
        Spacer(Modifier.width(8.dp))
        if (state.diagnostics.isNotEmpty()) {
            val errorColor = MaterialTheme.colorScheme.error
            val warnColor = Color(0xFFFFB300)
            TextButton(onClick = onOpenProblems) {
                Text(
                    text = "\u26A0 ${state.errorCount}/${state.warningCount}",
                    color = if (state.errorCount > 0) errorColor else warnColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(
            text = tab.language.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
        Text(
            text = "${tab.charsetName} \u00B7 ${tab.lineEndingName}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** A banner shown when the open file changed on disk under unsaved edits: reload or keep. */
@Composable
internal fun DiskChangedBanner(onReload: () -> Unit, onKeep: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.disk_changed),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReload) { Text(stringResource(R.string.reload)) }
        TextButton(onClick = onKeep) { Text(stringResource(R.string.keep_mine)) }
    }
}

/** A small dialog that reads a line number and jumps the caret to it. */
@Composable
internal fun GoToLineDialog(onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val line = value.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_line)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { new -> value = new.filter { it.isDigit() } },
                singleLine = true,
                label = { Text(stringResource(R.string.line)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { line?.let(onGo); onDismiss() },
                enabled = line != null && line > 0,
            ) { Text(stringResource(R.string.go)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

/** The command-palette registry: named actions mapped to editor callbacks and nav destinations. */
@Composable
internal fun editorCommands(
    actions: CodeActions,
    onToggleFind: () -> Unit,
    onGoToLine: () -> Unit,
    onQuickOpen: () -> Unit,
    onOutline: () -> Unit,
    onResolveConflicts: () -> Unit,
    onProblems: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenFile: () -> Unit,
): List<PickerItem> = listOf(
    PickerItem(stringResource(R.string.quick_open)) { onQuickOpen() },
    PickerItem(stringResource(UiR.string.save)) { actions.save() },
    PickerItem(stringResource(R.string.save_all)) { actions.saveAll() },
    PickerItem(stringResource(R.string.find)) { onToggleFind() },
    PickerItem(stringResource(R.string.go_to_line)) { onGoToLine() },
    PickerItem(stringResource(R.string.go_to_symbol)) { onOutline() },
    PickerItem(stringResource(R.string.toggle_comment)) { actions.toggleComment() },
    PickerItem(stringResource(R.string.duplicate_line)) { actions.duplicateLine() },
    PickerItem(stringResource(R.string.move_line_up)) { actions.moveLineUp() },
    PickerItem(stringResource(R.string.move_line_down)) { actions.moveLineDown() },
    PickerItem(stringResource(R.string.delete_line)) { actions.deleteLine() },
    PickerItem(stringResource(R.string.format_document)) { actions.formatDocument() },
    PickerItem(stringResource(R.string.resolve_conflicts)) { onResolveConflicts() },
    PickerItem(stringResource(R.string.problems)) { onProblems() },
    PickerItem(stringResource(R.string.split_view)) { actions.toggleSplit() },
    PickerItem(stringResource(R.string.soft_wrap)) { actions.toggleSoftWrap() },
    PickerItem(stringResource(UiR.string.undo)) { actions.undo() },
    PickerItem(stringResource(R.string.redo)) { actions.redo() },
    PickerItem(stringResource(R.string.search_in_project)) { onOpenSearch() },
    PickerItem(stringResource(R.string.source_control)) { onOpenGit() },
    PickerItem(stringResource(R.string.terminal)) { onOpenTerminal() },
    PickerItem(stringResource(R.string.preview)) { onOpenPreview() },
    PickerItem(stringResource(UiR.string.settings)) { onOpenSettings() },
    PickerItem(stringResource(R.string.open_folder)) { onOpenFolder() },
    PickerItem(stringResource(R.string.open_file)) { onOpenFile() },
)
