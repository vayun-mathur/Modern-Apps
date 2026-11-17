package com.vayunmathur.code.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.domain.Resolution
import com.vayunmathur.code.domain.parseConflicts
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

private val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/**
 * A merge-conflict resolver: one card per conflict showing both sides, with an Ours/Theirs/Both
 * choice. Confirm applies the choices (in document order) back through the editor.
 */
@Composable
fun MergeResolverDialog(
    text: String,
    onResolve: (List<Resolution>) -> Unit,
    onDismiss: () -> Unit,
) {
    val conflicts = remember(text) { parseConflicts(text) }
    val choices = remember(conflicts.size) {
        mutableStateListOf<Resolution>().apply { repeat(conflicts.size) { add(Resolution.OURS) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resolve_conflicts)) },
        text = {
            if (conflicts.isEmpty()) {
                Text(
                    stringResource(R.string.no_conflicts),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(conflicts.indices.toList()) { index ->
                        ConflictCard(
                            ours = conflicts[index].ours.joinToString("\n"),
                            theirs = conflicts[index].theirs.joinToString("\n"),
                            selected = choices[index],
                            onSelect = { choices[index] = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onResolve(choices.toList()); onDismiss() },
                enabled = conflicts.isNotEmpty(),
            ) { Text(stringResource(UiR.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

@Composable
private fun ConflictCard(
    ours: String,
    theirs: String,
    selected: Resolution,
    onSelect: (Resolution) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.conflict_ours), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(ours, style = MONO, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.conflict_theirs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(theirs, style = MONO, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth()) {
            ChoiceButton(stringResource(R.string.conflict_keep_ours), selected == Resolution.OURS) { onSelect(Resolution.OURS) }
            ChoiceButton(stringResource(R.string.conflict_keep_theirs), selected == Resolution.THEIRS) { onSelect(Resolution.THEIRS) }
            ChoiceButton(stringResource(R.string.conflict_keep_both), selected == Resolution.BOTH) { onSelect(Resolution.BOTH) }
        }
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
