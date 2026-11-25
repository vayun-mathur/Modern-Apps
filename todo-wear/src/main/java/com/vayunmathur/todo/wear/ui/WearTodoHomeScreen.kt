package com.vayunmathur.todo.wear.ui

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
import com.vayunmathur.todo.wear.R
import com.vayunmathur.todo.wear.data.WearTodoStore
import com.vayunmathur.todo.wear.sync.WearLinkBridge
import kotlinx.coroutines.launch

/**
 * Watch home = the phone app's drawer: smart views in phone order, then
 * lists, then tags, each with its open-task count, plus quick add. Rendered
 * from the cached catalog, so it works with the phone absent.
 */
@Composable
fun WearTodoHomeScreen(
    store: WearTodoStore,
    onOpenPage: (String) -> Unit,
    onQuickAdd: () -> Unit,
) {
    val catalog by store.catalog.collectAsState()
    val pending by store.pendingOps.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var syncNote by remember { mutableStateOf<String?>(null) }
    val requestedText = stringResource(R.string.wear_sync_requested)
    val noPhoneText = stringResource(R.string.wear_no_phone)

    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (pending.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_pending, pending.size),
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
                onClick = onQuickAdd,
                label = { Text(stringResource(R.string.wear_quick_add), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        if (catalog.views.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(catalog.views, key = { it.key }) { view ->
                Chip(
                    onClick = { onOpenPage(view.key) },
                    label = { Text(view.title.ifBlank { view.key }, maxLines = 1) },
                    secondaryLabel = {
                        Text("${view.openCount} open", maxLines = 1)
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
                        syncNote = if (WearLinkBridge.requestSync(context)) {
                            requestedText
                        } else {
                            noPhoneText
                        }
                    }
                },
                label = { Text(stringResource(R.string.wear_sync), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
