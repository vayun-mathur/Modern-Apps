package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconDirections
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.data.ParkingSpot

/**
 * Small parking sheet (P9): shows when the car was parked, lets the user add a
 * note, clear the spot ("found my car"), or get directions back to it through
 * the existing routing path. Uses the shared [ModalBottomSheet] (no raw
 * scaffold), mirroring [LayersSheet].
 *
 * The note is persisted once, when the sheet is dismissed or an action is
 * taken, to avoid a DataStore write per keystroke.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParkingSheet(
    spot: ParkingSpot,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onDirections: () -> Unit,
    onNoteChange: (String) -> Unit,
) {
    var note by remember(spot.timestamp) { mutableStateOf(spot.note ?: "") }
    val savedAt = remember(spot.timestamp) {
        java.text.DateFormat
            .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(spot.timestamp))
    }

    ModalBottomSheet(onDismissRequest = { onNoteChange(note); onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text(
                stringResource(R.string.parking_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.parking_saved_at, savedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text(stringResource(R.string.parking_note_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onNoteChange(note); onDirections() },
                    modifier = Modifier.weight(1f),
                ) {
                    IconDirections(Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.directions))
                }
                TextButton(onClick = onClear) {
                    Text(stringResource(UiR.string.clear))
                }
            }
        }
    }
}
