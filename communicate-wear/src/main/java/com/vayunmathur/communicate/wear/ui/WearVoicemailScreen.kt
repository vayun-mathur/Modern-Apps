package com.vayunmathur.communicate.wear.ui

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
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.communicate.wear.R
import com.vayunmathur.communicate.wear.data.WearCommunicateStore

/**
 * Voicemail indicators only: caller, duration, read state and a capped
 * transcript when the phone has one. No audio crosses, so there is no
 * playback here; mark-read is executed on the phone.
 */
@Composable
fun WearVoicemailScreen(
    store: WearCommunicateStore,
    onBack: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()

    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                stringResource(R.string.wear_voicemail),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (snapshot.voicemails.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(snapshot.voicemails, key = { it.key }) { voicemail ->
                Chip(
                    onClick = {
                        if (!voicemail.isRead) store.queueMarkVoicemailRead(voicemail)
                    },
                    label = { Text(voicemail.phoneNumber, maxLines = 1) },
                    secondaryLabel = {
                        val state = if (voicemail.isRead) "" else " · unread"
                        val text = "${voicemail.line} · ${voicemail.durationSeconds}s$state" +
                            if (voicemail.transcript.isNotBlank()) {
                                " · ${voicemail.transcript}"
                            } else {
                                ""
                            }
                        Text(text, maxLines = 1)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        item {
            Text(
                stringResource(R.string.wear_listen_on_phone),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
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
