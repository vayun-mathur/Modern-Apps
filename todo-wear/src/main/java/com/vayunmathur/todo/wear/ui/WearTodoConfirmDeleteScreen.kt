package com.vayunmathur.todo.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.vayunmathur.todo.wear.R
import com.vayunmathur.todo.wear.data.WearTodoStore

/** Delete needs an explicit confirmation on the watch (no swipe-to-delete). */
@Composable
fun WearTodoConfirmDeleteScreen(
    store: WearTodoStore,
    syncId: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                stringResource(R.string.wear_delete_confirm),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            Chip(
                onClick = {
                    store.delete(syncId)
                    onDone()
                },
                label = { Text(stringResource(R.string.wear_delete), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = onCancel,
                label = { Text(stringResource(R.string.wear_cancel), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
