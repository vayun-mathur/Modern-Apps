package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.InputChip
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.todo.R
import com.vayunmathur.library.ui.R as UiR

/** Edit a task's tags: remove with ×, type to add, tap a suggestion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorDialog(tags: List<String>, allTags: List<String>, onSet: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf(tags) }
    var input by remember { mutableStateOf("") }
    val add = {
        val tag = input.trim().removePrefix("#").replace(' ', '-')
        if (tag.isNotEmpty() && current.none { it.equals(tag, true) }) current = current + tag
        input = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.forEach { tag ->
                        InputChip(
                            selected = true,
                            onClick = { current = current - tag },
                            label = { Text(tag) },
                            trailingIcon = { IconClose() },
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.add_tag)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                val suggestions = allTags.filter { s ->
                    current.none { it.equals(s, true) } && s.contains(input.trim().removePrefix("#"), true)
                }.take(MAX_SUGGESTIONS)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s -> AssistChip(onClick = { current = current + s }, label = { Text("#$s") }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                add()
                onSet(current)
            }) { Text(stringResource(R.string.done_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}

private const val MAX_SUGGESTIONS = 12
