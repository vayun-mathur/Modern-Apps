package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.findfamily.R
import androidx.compose.runtime.Composable
import com.vayunmathur.findfamily.ui.dialogs.NoShowAlertDialog
import com.vayunmathur.findfamily.notifications.FindFamilyNotificationChannels
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.platform.PersonActions
import com.vayunmathur.findfamily.platform.PersonUiState

@Composable
fun PersonDetailSheet(state: PersonUiState, actions: PersonActions) {
    val user = state.user
    var showNoShowDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
        UserCard(user, state.location, true) {}
        Spacer(Modifier.height(8.dp))
        // Sharing controls are hidden for your own entry: you can't "stop sharing with
        // yourself", and doing so per-person was a confusing way to try to turn the
        // service off. Use the Quick Settings tile to disable tracking (GitHub #487).
        if (user.id != Networking.userid) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.share_your_location),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    user.sendingEnabled,
                    { send -> actions.setUserSharing(user, send) },
                    enabled = state.sharingGloballyEnabled
                )
            }
            if (!state.sharingGloballyEnabled) {
                Text(
                    stringResource(R.string.sharing_paused_note),
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Auto-toggle: "Turn on/off after" + duration/arrival dropdown (Never default)
            Spacer(Modifier.height(4.dp))
            AutoToggleRow(user, state.waypoints, actions)
            Spacer(Modifier.height(4.dp))
            // Per-person arrival/departure notification settings (issue #618). Each opens the
            // system channel settings so sound/vibration/DND can be tuned independently.
            val context = LocalContext.current
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    {
                        FindFamilyNotificationChannels.openChannelSettings(
                            context, user.id, user.name, arrival = true
                        )
                    },
                    Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.notification_settings_arrival))
                }
                OutlinedButton(
                    {
                        FindFamilyNotificationChannels.openChannelSettings(
                            context, user.id, user.name, arrival = false
                        )
                    },
                    Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.notification_settings_departure))
                }
            }
            Spacer(Modifier.height(4.dp))
            // No-show watch (issue 702): expected arrival that never happens.
            OutlinedButton(
                { showNoShowDialog = true },
                Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.noshow_watch_button))
            }
            Spacer(Modifier.height(4.dp))
        }
        OutlinedButton(
            { actions.changeConnectedContact() },
            Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.change_connected_contact))
        }
    }
    if (showNoShowDialog) {
        NoShowAlertDialog(
            user = user,
            waypoints = state.waypoints,
            actions = actions,
            onDismiss = { showNoShowDialog = false },
        )
    }
}
