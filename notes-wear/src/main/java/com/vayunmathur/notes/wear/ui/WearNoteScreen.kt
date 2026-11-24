package com.vayunmathur.notes.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.notes.wear.R
import com.vayunmathur.notes.wear.data.WearNotesStore

/**
 * One note: title plus its (bounded) full text. Edit is offered only for
 * text-only notes; delete for every note. Both queue an op for the phone —
 * this screen never claims the phone has applied anything.
 */
@Composable
fun WearNoteScreen(
    store: WearNotesStore,
    syncId: String,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val notes by store.notes.collectAsState()
    val note = notes.firstOrNull { it.syncId == syncId }
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(state = listState) {
        if (note == null) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            item {
                Text(
                    note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            if (note.body.isNotBlank()) {
                item {
                    Text(
                        note.body,
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (note.bodyTruncated) {
                item {
                    Text(
                        stringResource(R.string.wear_body_truncated),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (!note.textOnly) {
                item {
                    Text(
                        stringResource(R.string.wear_read_only_rich),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            } else {
                item {
                    Chip(
                        onClick = { onEdit(note.syncId) },
                        label = { Text(stringResource(R.string.wear_edit), maxLines = 1) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
            item {
                Chip(
                    onClick = {
                        store.deleteNote(note.syncId)
                        onBack()
                    },
                    label = { Text(stringResource(R.string.wear_delete), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        item {
            Chip(
                onClick = onBack,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
