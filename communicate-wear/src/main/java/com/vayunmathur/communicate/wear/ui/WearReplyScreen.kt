package com.vayunmathur.communicate.wear.ui

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
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.communicate.wear.R
import com.vayunmathur.communicate.wear.data.WearCommunicateStore
import com.vayunmathur.communicate.wear.domain.WearCommunicateProtocol

/**
 * Quick reply: canned replies plus a free-text field (the watch IME's
 * microphone is the dictation path). Sending queues a QuickReply op; the
 * phone executes it and its verdict clears the pending state.
 */
@Composable
fun WearReplyScreen(
    store: WearCommunicateStore,
    threadKey: String,
    onDone: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()
    val thread = snapshot.threads.firstOrNull { it.key == threadKey }
    var draft by remember(threadKey) { mutableStateOf("") }
    val textStyle = TextStyle(color = MaterialTheme.colors.onSurface)

    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                stringResource(R.string.wear_reply_hint),
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            Chip(
                onClick = {
                    if (thread != null && store.queueReply(thread, draft) != null) onDone()
                },
                label = { Text(stringResource(R.string.wear_send), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        items(WearCommunicateProtocol.CANNED_REPLIES) { canned ->
            Chip(
                onClick = {
                    if (thread != null && store.queueReply(thread, canned) != null) onDone()
                },
                label = { Text(canned, maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
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
