package com.vayunmathur.notes.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
 * Quick-note create (syncId = null) and text-only edit. The text fields use
 * the watch system IME, whose microphone is the dictation path — speaking
 * the title/body is the intended way to fill them on a round screen. Save
 * queues the op locally; it is pending until the phone reflects it.
 */
@Composable
fun WearNoteEditorScreen(
    store: WearNotesStore,
    syncId: String?,
    onDone: () -> Unit,
) {
    val notes by store.notes.collectAsState()
    val existing = syncId?.let { id -> notes.firstOrNull { it.syncId == id } }
    var title by remember(syncId) { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember(syncId) { mutableStateOf(existing?.body.orEmpty()) }
    val listState = rememberScalingLazyListState()
    val textStyle = TextStyle(color = MaterialTheme.colors.onSurface)

    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.wear_title_hint),
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            Text(
                stringResource(R.string.wear_body_hint),
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            BasicTextField(
                value = body,
                onValueChange = { body = it },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            Chip(
                onClick = {
                    if (syncId == null) {
                        store.createNote(title.trim(), body.trim())
                    } else {
                        store.editNote(syncId, title.trim(), body.trim())
                    }
                    onDone()
                },
                label = { Text(stringResource(R.string.wear_save), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = onDone,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
