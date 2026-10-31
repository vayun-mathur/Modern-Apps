package com.vayunmathur.todo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.library.ui.R as UiR

/** Pick a list, or the Inbox (null). */
@Composable
fun ListPickerDialog(
    lists: List<TodoList>,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.view_inbox)) },
                    leadingContent = { IconInbox() },
                    trailingContent = if (current == null) ({ IconCheck() }) else null,
                    modifier = Modifier.clickable { onPick(null) },
                )
                lists.forEach { list ->
                    ListItem(
                        headlineContent = { Text(list.name) },
                        leadingContent = {
                            val color = list.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                            Box(Modifier.size(16.dp).background(color, CircleShape))
                        },
                        trailingContent = if (current == list.syncId) ({ IconCheck() }) else null,
                        modifier = Modifier.clickable { onPick(list.syncId) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}
