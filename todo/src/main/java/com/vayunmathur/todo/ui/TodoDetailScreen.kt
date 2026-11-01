package com.vayunmathur.todo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CheckboxDefaults
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconMove
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OdfMarkdownEditorField
import com.vayunmathur.library.ui.OdfMarkdownEditorToolbar
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberOdfMarkdownEditorController
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.todo.R
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoUiState
import com.vayunmathur.library.ui.R as UiR

/**
 * The todo editor, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailScreen(state: TodoUiState, actions: TodoActions) {
    val todo = state.todo
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var pickList by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(state.isNew) { if (state.isNew) titleFocus.requestFocus() }
    val notes = key(todo.syncId) {
        rememberOdfMarkdownEditorController(initialMarkdown = todo.notes) { md -> actions.update { it.copy(notes = md) } }
    }

    DetailScaffold(
        title = stringResource(R.string.todo_details),
        onNavigateBack = { actions.back() },
        scrollBehavior = appBarScrollBehavior(),
        bottomBar = {
            // EditorBottomBar claims navigationBars ∪ ime itself; no extra imePadding.
            if (notes.focused) OdfMarkdownEditorToolbar(notes)
        },
        actions = {
            IconButton(onClick = { showDeleteConfirm = true }) { IconDelete() }
            IconButton(onClick = { menu = true }) { IconMoreVert() }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.duplicate)) },
                    leadingIcon = { IconCopy() },
                    enabled = todo.title.isNotBlank(),
                    onClick = {
                        menu = false
                        actions.duplicate()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_to)) },
                    leadingIcon = { IconMove() },
                    onClick = {
                        menu = false
                        pickList = true
                    },
                )
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint = priorityColor(todo.priority)
            Checkbox(
                checked = todo.done,
                onCheckedChange = { actions.setDone(it) },
                colors = if (tint != null) CheckboxDefaults.colors(uncheckedColor = tint, checkedColor = tint) else CheckboxDefaults.colors(),
            )
            TextField(
                value = todo.title,
                onValueChange = { title -> actions.update { it.copy(title = title) } },
                modifier = Modifier.weight(1f).focusRequester(titleFocus)
                    .then(if (todo.id != 0L) Modifier.sharedContainer(sharedTitleKey(todo.id)) else Modifier),
                placeholder = { Text(stringResource(R.string.todo_title)) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
        EditorChips(state, actions, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Text(
            stringResource(R.string.todo_notes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        )
        OdfMarkdownEditorField(
            controller = notes,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
        if (!todo.isSubtask) {
            SubtaskList(
                subtasks = state.subtasks,
                canAdd = todo.title.isNotBlank(),
                actions = actions,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (!state.isNew) EditorMetaFooter(todo, Modifier.padding(vertical = 16.dp))
    }

    if (pickList) {
        ListSectionPickerDialog(state.lists, todo.listSyncId, todo.section, onPick = { list, section ->
            actions.update { it.copy(listSyncId = list, section = section) }
            pickList = false
        }, onDismiss = { pickList = false })
    }
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.delete_todo),
            message = stringResource(R.string.delete_todo_confirm),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = { actions.deleteTodo() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
