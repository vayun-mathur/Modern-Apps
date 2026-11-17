package com.vayunmathur.code.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.platform.CodeUiState
import com.vayunmathur.code.domain.fuzzyRank
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * One selectable row in the fuzzy picker. [matchKey] is what the query is scored against (the file
 * path for quick-open, the command title for the palette); [primary]/[secondary] are what is drawn.
 */
data class PickerItem(
    val primary: String,
    val secondary: String? = null,
    val matchKey: String = primary,
    val onSelect: () -> Unit,
)

private const val MAX_PICKER_RESULTS = 50

/**
 * A keyboard-first fuzzy picker used by both quick-open and the command palette: a text field over
 * a filtered, ranked list. Selecting a row runs its action and dismisses. When the query is blank
 * [emptyQueryItems] is shown as-is (quick-open uses it for recent files).
 */
@Composable
fun FuzzyPickerDialog(
    title: String,
    placeholder: String,
    items: List<PickerItem>,
    onDismiss: () -> Unit,
    emptyQueryItems: List<PickerItem> = items,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items, emptyQueryItems) {
        if (query.isBlank()) emptyQueryItems else fuzzyRank(query, items) { it.matchKey }
    }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(placeholder) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                Spacer(Modifier.padding(4.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.no_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(filtered.take(MAX_PICKER_RESULTS)) { row ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { row.onSelect(); onDismiss() }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                row.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            row.secondary?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.close)) }
        },
    )

    // Focus the field on open so the keyboard is ready to type immediately.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

/** Quick-open rows: every project file, each opening its path when chosen. */
fun quickOpenItems(state: CodeUiState, onOpenPath: (String) -> Unit): List<PickerItem> =
    state.projectFiles.map { file ->
        PickerItem(file.name, file.relativePath, file.relativePath) { onOpenPath(file.path) }
    }

/** Recent-file rows shown by quick-open when the query is empty. */
fun recentOpenItems(state: CodeUiState, onOpenPath: (String) -> Unit): List<PickerItem> =
    state.recentFiles.map { file ->
        PickerItem(file.name, file.relativePath, file.relativePath) { onOpenPath(file.path) }
    }
