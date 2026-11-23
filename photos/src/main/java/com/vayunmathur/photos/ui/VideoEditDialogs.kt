package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.photos.R

@Composable
internal fun SaveDialog(
    onDismiss: () -> Unit,
    onSaveCopy: () -> Unit,
    onOverwrite: () -> Unit,
) {
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_save)) },
        text = { Text(stringResource(R.string.video_save_prompt)) },
        confirmButton = {
            TextButton(onClick = onSaveCopy) { Text(stringResource(R.string.action_save_as_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onOverwrite) { Text(stringResource(R.string.video_overwrite)) }
        },
    )
}

@Composable
internal fun ExportProgressDialog(progress: Float, onCancel: () -> Unit) {
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.video_exporting)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress > 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
                } else {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(16.dp))
                Text("${(progress * 100).toInt()}%")
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(com.vayunmathur.library.ui.R.string.cancel)) }
        },
    )
}

/** Formats a video position/duration in milliseconds as m:ss (or h:mm:ss past an hour). */
internal fun formatVideoTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
