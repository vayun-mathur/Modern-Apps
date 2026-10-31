package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.platform.TodoActions

/** Checklist under a task: progress, inline-editable rows, and an add field. */
@Composable
fun SubtaskList(subtasks: List<Todo>, canAdd: Boolean, actions: TodoActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.subtasks), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (subtasks.isNotEmpty()) {
            val done = subtasks.count { it.done }
            LinearProgressIndicator(
                progress = { done.toFloat() / subtasks.size },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        subtasks.forEach { sub -> SubtaskRow(sub, actions) }
        var text by remember { mutableStateOf("") }
        val add = {
            actions.addSubtask(text)
            text = ""
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = add, enabled = canAdd && text.isNotBlank()) { IconAdd() }
            TextField(
                value = text,
                onValueChange = { text = it },
                enabled = canAdd,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.add_subtask)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                colors = transparentFieldColors(),
            )
        }
    }
}

@Composable
private fun SubtaskRow(sub: Todo, actions: TodoActions) {
    var text by remember(sub.syncId) { mutableStateOf(sub.title) }
    val commit = { if (text != sub.title) actions.updateSubtask(sub.copy(title = text)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = sub.done, onCheckedChange = { actions.toggleSubtask(sub) })
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) commit() },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (sub.done) TextDecoration.LineThrough else null,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            colors = transparentFieldColors(),
        )
        IconButton(onClick = { actions.deleteSubtask(sub) }) { IconClose() }
    }
}

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)
