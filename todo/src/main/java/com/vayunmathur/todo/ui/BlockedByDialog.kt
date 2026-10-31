package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.library.ui.R as UiR

/** Choose which open tasks must be done before this one. */
@Composable
fun BlockedByDialog(candidates: List<Todo>, selected: List<String>, onSet: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(selected.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blocked_by)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(candidates, key = { it.syncId }) { todo ->
                    val on = todo.syncId in chosen
                    val toggle = { chosen = if (on) chosen - todo.syncId else chosen + todo.syncId }
                    ListItem(
                        headlineContent = { Text(todo.title.ifBlank { stringResource(R.string.untitled) }) },
                        leadingContent = { Checkbox(checked = on, onCheckedChange = { toggle() }) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSet(chosen.toList()) }) { Text(stringResource(R.string.done_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}
