package com.vayunmathur.todo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.library.ui.R as UiR

/** Create or edit a list: name, colour, icon and sections. Deleting moves its tasks to the Inbox. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListEditDialog(list: TodoList?, onSave: (TodoList) -> Unit, onDelete: ((TodoList) -> Unit)?, onDismiss: () -> Unit) {
    val base = list ?: TodoList()
    var name by remember { mutableStateOf(base.name) }
    var color by remember { mutableStateOf(base.color) }
    var icon by remember { mutableStateOf(base.icon) }
    var sections by remember { mutableStateOf(base.sections) }
    var newSection by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (list == null) R.string.new_list else R.string.edit_list)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.list_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LIST_COLORS.forEach { c ->
                        val selected = color == c
                        Box(
                            Modifier.size(32.dp).background(Color(c), CircleShape)
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LIST_ICON_KEYS.forEach { key ->
                        val selected = (icon ?: "list") == key
                        IconButton(onClick = { icon = key }) {
                            ListIcon(key, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(stringResource(R.string.sections), style = MaterialTheme.typography.titleSmall)
                sections.forEachIndexed { i, section ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(section, modifier = Modifier.weight(1f))
                        if (i > 0) {
                            IconButton(onClick = { sections = sections.toMutableList().apply { add(i - 1, removeAt(i)) } }) {
                                IconKeyboardArrowUp()
                            }
                        }
                        IconButton(onClick = { sections = sections - section }) { IconClose() }
                    }
                }
                OutlinedTextField(
                    value = newSection,
                    onValueChange = { newSection = it },
                    placeholder = { Text(stringResource(R.string.add_section)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = {
                            val s = newSection.trim()
                            if (s.isNotEmpty() && s !in sections) sections = sections + s
                            newSection = ""
                        }) { Text(stringResource(R.string.add)) }
                    },
                )
                if (list != null && onDelete != null) {
                    TextButton(onClick = { onDelete(list) }) {
                        Text(stringResource(R.string.delete_list), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(base.copy(name = name.trim(), color = color, icon = icon, sections = sections)) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}

private val LIST_COLORS = listOf(
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF6D4C41.toInt(),
)
