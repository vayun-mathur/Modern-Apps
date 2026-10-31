package com.vayunmathur.todo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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

/** Pick the Inbox, a list, or a section inside a list. */
@Composable
fun ListSectionPickerDialog(
    lists: List<TodoList>,
    currentList: String?,
    currentSection: String?,
    onPick: (listSyncId: String?, section: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val check: @Composable (Boolean) -> (@Composable () -> Unit)? = { on -> if (on) ({ IconCheck() }) else null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.view_inbox)) },
                    leadingContent = { IconInbox() },
                    trailingContent = check(currentList == null),
                    modifier = Modifier.clickable { onPick(null, null) },
                )
                lists.forEach { list ->
                    ListItem(
                        headlineContent = { Text(list.name) },
                        leadingContent = {
                            val color = list.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                            Box(Modifier.size(16.dp).background(color, CircleShape))
                        },
                        trailingContent = check(currentList == list.syncId && currentSection == null),
                        modifier = Modifier.clickable { onPick(list.syncId, null) },
                    )
                    list.sections.forEach { section ->
                        ListItem(
                            headlineContent = { Text(section) },
                            trailingContent = check(currentList == list.syncId && currentSection == section),
                            modifier = Modifier.clickable { onPick(list.syncId, section) }.padding(start = 32.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}
