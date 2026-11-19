package com.vayunmathur.code.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.Route
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.domain.UserSnippet
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

/**
 * Manager for user-defined snippets: add/edit/delete `(trigger, template, language?)` entries that
 * the ViewModel persists and merges into autocomplete. Templates use `$0` for the caret position.
 */
@Composable
fun SnippetsPage(viewModel: EditorViewModel, backStack: NavBackStack<Route>) {
    // null = no dialog, -1 = adding a new snippet, >=0 = editing that index.
    var editing by remember { mutableStateOf<Int?>(null) }

    AppScaffold(title = stringResource(R.string.user_snippets), backStack = backStack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Button(onClick = { editing = -1 }, modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.add_snippet))
            }
            if (viewModel.userSnippets.isEmpty()) {
                Text(
                    stringResource(R.string.no_snippets),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(viewModel.userSnippets) { index, snippet ->
                        SnippetRow(
                            snippet = snippet,
                            onEdit = { editing = index },
                            onDelete = { viewModel.deleteSnippet(index) },
                        )
                    }
                }
            }
        }
    }

    val idx = editing
    if (idx != null) {
        val existing = if (idx >= 0) viewModel.userSnippets.getOrNull(idx) else null
        SnippetEditDialog(
            initial = existing,
            onSave = { snippet ->
                if (idx >= 0) viewModel.updateSnippet(idx, snippet) else viewModel.addSnippet(snippet)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SnippetRow(snippet: UserSnippet, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(snippet.trigger, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                languageLabel(snippet.languageId),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) { IconEdit() }
        IconButton(onClick = onDelete) { IconDelete() }
    }
}

@Composable
private fun SnippetEditDialog(
    initial: UserSnippet?,
    onSave: (UserSnippet) -> Unit,
    onDismiss: () -> Unit,
) {
    var trigger by remember { mutableStateOf(initial?.trigger ?: "") }
    var template by remember { mutableStateOf(initial?.template ?: "") }
    var languageId by remember { mutableStateOf(initial?.languageId) }
    var showLanguage by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.add_snippet else R.string.edit_snippet))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.snippet_trigger)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text(stringResource(R.string.snippet_template)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.snippet_caret_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showLanguage = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.language) + ": ")
                    Text(languageLabel(languageId), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(UserSnippet(trigger.trim(), template, languageId)) },
                enabled = trigger.isNotBlank(),
            ) { Text(stringResource(UiR.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )

    if (showLanguage) {
        LanguageChoiceDialog(
            selected = languageId,
            onSelect = { languageId = it },
            onDismiss = { showLanguage = false },
        )
    }
}

/** Single-choice dialog over "All languages" plus every [Language]; value is the language name or null. */
@Composable
private fun LanguageChoiceDialog(
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options: List<Pair<String?, String>> =
        listOf(null to stringResource(R.string.all_languages)) + Language.entries.map { it.name to it.label }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            LazyColumn {
                itemsIndexed(options) { _, (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == value, onClick = { onSelect(value); onDismiss() })
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.close)) }
        },
    )
}

@Composable
private fun languageLabel(languageId: String?): String =
    if (languageId == null) {
        stringResource(R.string.all_languages)
    } else {
        Language.entries.firstOrNull { it.name == languageId }?.label ?: languageId
    }
