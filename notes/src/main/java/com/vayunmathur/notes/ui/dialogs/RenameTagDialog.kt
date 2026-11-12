package com.vayunmathur.notes.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.notes.R
import com.vayunmathur.notes.domain.normalizeTag
import com.vayunmathur.notes.domain.tagKey
import com.vayunmathur.library.ui.R as UiR

/**
 * Renames [tag] across every note. [existingTags] lets the dialog say up front
 * when the new name is another tag that already exists, in which case the two
 * merge. Rename is disabled while the name is blank or unchanged.
 */
@Composable
fun RenameTagDialog(
    tag: String,
    existingTags: List<String>,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable(tag) { mutableStateOf(tag) }
    val target = normalizeTag(input)
    val mergesInto = target?.let { t ->
        existingTags.firstOrNull { tagKey(it) == tagKey(t) && tagKey(it) != tagKey(tag) }
    }
    // A case-only change ("work" -> "Work") is a real rename; an identical name is not.
    val canRename = target != null && target != tag
    val confirm = { if (canRename && target != null) { onDismiss(); onRename(target) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_tag_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tag_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
                if (mergesInto != null) {
                    Text(
                        stringResource(R.string.rename_tag_merges, mergesInto),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = canRename) { Text(stringResource(UiR.string.rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}
