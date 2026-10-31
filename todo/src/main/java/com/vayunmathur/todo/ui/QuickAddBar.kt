package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.QuickAddParser
import kotlinx.datetime.TimeZone

/**
 * Inline add field. Text is parsed as you type and the recognised pieces show as chips; a
 * multi-line paste offers to create one task per line.
 */
@Composable
fun QuickAddBar(
    lists: List<QuickAddParser.ListName>,
    onAdd: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
    leading: (@Composable () -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initialText) }
    var pasted by remember { mutableStateOf<List<String>?>(null) }
    val parsed = remember(text, lists) {
        QuickAddParser.parse(text, System.currentTimeMillis(), TimeZone.currentSystemDefault(), lists)
    }
    val submit = {
        if (text.isNotBlank()) onAdd(listOf(text))
        text = ""
    }
    val submitLabel = stringResource(R.string.add_task)

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                val lines = value.lines().map { it.trim() }.filter { it.isNotEmpty() }
                when {
                    '\n' !in value -> text = value
                    lines.size > 1 -> pasted = lines
                    else -> {
                        text = lines.firstOrNull().orEmpty()
                        submit()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.add_task_hint)) },
            leadingIcon = leading,
            trailingIcon = {
                IconButton(
                    onClick = submit,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.semantics { contentDescription = submitLabel },
                ) { IconSend() }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            maxLines = 3,
            shape = RoundedCornerShape(28.dp),
        )
        if (parsed.tokens.isNotEmpty()) QuickAddPreview(parsed, lists)
    }

    pasted?.let { lines ->
        AlertDialog(
            onDismissRequest = { pasted = null },
            title = { Text(stringResource(R.string.paste_title)) },
            text = { Text(lines.take(PREVIEW_LINES).joinToString("\n") + if (lines.size > PREVIEW_LINES) "\n…" else "") },
            confirmButton = {
                TextButton(onClick = {
                    onAdd(lines)
                    pasted = null
                    text = ""
                }) { Text(pluralStringResource(R.plurals.add_n_tasks, lines.size, lines.size)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    text = lines.joinToString(" ")
                    pasted = null
                }) { Text(stringResource(R.string.add_as_one)) }
            },
        )
    }
}

/** The parsed pieces of the quick-add text, as read-only chips. */
@Composable
private fun QuickAddPreview(parsed: QuickAddParser.Result, lists: List<QuickAddParser.ListName>) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickAddChips(parsed, lists)
    }
}

private const val PREVIEW_LINES = 6
