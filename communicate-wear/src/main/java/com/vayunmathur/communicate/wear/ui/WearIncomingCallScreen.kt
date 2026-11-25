package com.vayunmathur.communicate.wear.ui

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
import com.vayunmathur.communicate.wear.R
import com.vayunmathur.communicate.wear.data.WearCommunicateStore
import com.vayunmathur.communicate.wear.domain.WearIncomingCall

/**
 * Incoming-call alert. Decline only: answering on the watch is not offered
 * because watch audio routing is not guaranteed for these lines, so no
 * Answer control is shown at all.
 */
@Composable
fun WearIncomingCallScreen(
    store: WearCommunicateStore,
    call: WearIncomingCall,
    onDone: () -> Unit,
) {
    WearIncomingCallContent(call = call, onDecline = { store.queueDeclineCall(call); onDone() }, onDone = onDone)
}

/** Stateless incoming-call behind [WearIncomingCallScreen]: call is a parameter for previews. */
@Composable
internal fun WearIncomingCallContent(call: WearIncomingCall, onDecline: () -> Unit, onDone: () -> Unit) {
    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                stringResource(R.string.wear_incoming_call),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            Text(
                call.displayName.ifBlank { call.line.name },
                style = MaterialTheme.typography.body1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            Text(
                stringResource(R.string.wear_answer_on_phone),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            Chip(
                onClick = onDecline,
                label = { Text(stringResource(R.string.wear_decline), maxLines = 1) },
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
