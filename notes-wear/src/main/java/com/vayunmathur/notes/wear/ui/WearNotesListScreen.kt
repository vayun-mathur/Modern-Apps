package com.vayunmathur.notes.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.notes.wear.R
import com.vayunmathur.notes.wear.data.WearNotesStore
import com.vayunmathur.notes.wear.sync.WearLinkBridge
import kotlinx.coroutines.launch

/** Round-screen recent-notes list: most recently updated first, from the local store (offline-safe). */
@Composable
fun WearNotesListScreen(
    store: WearNotesStore,
    onOpenNote: (String) -> Unit,
    onCreateNote: () -> Unit,
) {
    val notes by store.notes.collectAsState()
    val pending by store.pendingOps.collectAsState()
    val context = LocalContext.current
    WearNotesListContent(notes = notes, pendingCount = pending.size, onOpenNote = onOpenNote, onCreateNote = onCreateNote, onRequestSync = { WearLinkBridge.requestSync(context) })
}

/**
 * Stateless list body behind [WearNotesListScreen]: notes/pending count are parameters,
 * so Compose previews (src/screenshotTest) render literal fixtures with no store or phone.
 */
@Composable
internal fun WearNotesListContent(
    notes: List<com.vayunmathur.notes.wear.domain.WearNote>,
    pendingCount: Int,
    onOpenNote: (String) -> Unit,
    onCreateNote: () -> Unit,
    onRequestSync: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var syncNote by remember { mutableStateOf<String?>(null) }
    val requestedText = stringResource(R.string.wear_sync_requested)
    val noPhoneText = stringResource(R.string.wear_no_phone)
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (pendingCount > 0) {
            item {
                Text(
                    stringResource(R.string.wear_pending, pendingCount),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        syncNote?.let { note ->
            item {
                Text(
                    note,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        item {
            Chip(
                onClick = onCreateNote,
                label = { Text(stringResource(R.string.wear_new_note), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        if (notes.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(notes, key = { it.syncId }) { note ->
                Chip(
                    onClick = { onOpenNote(note.syncId) },
                    label = { Text(note.title.ifBlank { "Untitled" }, maxLines = 1) },
                    secondaryLabel = {
                        Text(note.body.replace('\n', ' ').take(60), maxLines = 1)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        item {
            Chip(
                onClick = {
                    scope.launch {
                        syncNote = if (onRequestSync()) requestedText else noPhoneText
                    }
                },
                label = { Text(stringResource(R.string.wear_request_sync), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
