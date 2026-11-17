package com.vayunmathur.everysync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.sync.SnapshotStore.SnapshotMeta
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.is24Hour
import kotlin.time.Instant

/**
 * Daily snapshot recovery: one row per restorable day (newest first) with a
 * confirm dialog. Restore replays records through the sinks as normal merge
 * input — newer edits win by LWW where they conflict, nothing is overwritten.
 */
@Composable
fun SnapshotSection(
    snapshots: List<SnapshotMeta>,
    restoring: Boolean,
    onRestore: (SnapshotMeta) -> Unit,
    onSnapshotNow: () -> Unit,
) {
    val context = LocalContext.current
    var pending: SnapshotMeta? by remember { mutableStateOf(null) }

    SettingsSection(title = stringResource(R.string.snapshots_title)) {
        Text(stringResource(R.string.snapshots_description))
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onSnapshotNow,
            enabled = !restoring,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.snapshot_take_now)) }
        Spacer(Modifier.height(8.dp))
        if (snapshots.isEmpty()) {
            Text(stringResource(R.string.snapshots_empty))
        }
        Column {
            snapshots.forEach { snapshot ->
                val day = DateString.dateTime(
                    Instant.fromEpochMilliseconds(snapshot.dayEpochMs),
                    is24Hour(context),
                )
                ListItem(
                    headlineContent = { Text(day) },
                    supportingContent = {
                        Text(
                            pluralStringResource(
                                R.plurals.snapshot_row,
                                snapshot.recordCount,
                                snapshot.dataType,
                                snapshot.recordCount,
                            ),
                        )
                    },
                    trailingContent = {
                        OutlinedButton(
                            onClick = { pending = snapshot },
                            enabled = !restoring,
                            modifier = Modifier.fillMaxWidth(0.4f),
                        ) { Text(stringResource(R.string.snapshot_restore)) }
                    },
                )
            }
        }
    }

    pending?.let { snapshot ->
        val day = DateString.dateTime(
            Instant.fromEpochMilliseconds(snapshot.dayEpochMs),
            is24Hour(context),
        )
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.snapshot_restore_title)) },
            text = { Text(stringResource(R.string.snapshot_restore_confirm, day, snapshot.dataType)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        onRestore(snapshot)
                    },
                ) { Text(stringResource(R.string.snapshot_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(com.vayunmathur.library.ui.R.string.cancel))
                }
            },
        )
    }
}
