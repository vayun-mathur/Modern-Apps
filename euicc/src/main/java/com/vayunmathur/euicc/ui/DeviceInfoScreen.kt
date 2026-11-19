package com.vayunmathur.euicc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.euicc.Route
import com.vayunmathur.euicc.platform.EuiccScreenState
import com.vayunmathur.euicc.ui.dialogs.EidDialog
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack

/**
 * The eUICC's own identity - EID and SGP.22 version - plus any notifications it is still
 * holding.
 *
 * Notifications live here rather than on the home screen because they are a protocol
 * detail: the eUICC queues one per install/enable/disable/delete for delivery back to the
 * SM-DP+, and a user reading a list of their SIMs has no use for them. They are surfaced
 * at all because a stuck notification is worth being able to see and clear.
 */
@Composable
fun DeviceInfoScreen(state: EuiccScreenState, backStack: NavBackStack<Route>, onRemoveNotification: (Int) -> Unit) {
    var showEidQr by remember { mutableStateOf(false) }
    val eid = state.eid
    val messenger = rememberMessenger()
    val removedMessage = stringResource(R.string.notification_removed)

    DetailScaffold(
        title = stringResource(R.string.device_info_title),
        backStack = backStack,
    ) {
        SettingsSection(title = stringResource(R.string.euicc_info_title)) {
            SettingsRow(
                title = stringResource(R.string.eid_number),
                supportingText = eid ?: stringResource(R.string.eid_not_found),
                enabled = eid != null,
                onClick = if (eid != null) ({ showEidQr = true }) else null,
            )
            SettingsRow(
                title = stringResource(R.string.sgp22_version),
                supportingText = state.info?.svn?.ifEmpty { null }
                    ?: stringResource(UiR.string.unknown),
            )
        }
        SettingsSection(title = stringResource(R.string.notifications_title)) {
            if (state.notifications.isEmpty()) {
                SettingsRow(title = stringResource(R.string.no_notifications), enabled = false)
            } else {
                for (note in state.notifications) {
                    SettingsRow(
                        title = stringResource(R.string.notification_title, note.seqNumber, note.operation),
                        supportingText = note.address,
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    onRemoveNotification(note.seqNumber)
                                    messenger.show(removedMessage)
                                },
                                enabled = !state.loading,
                            ) { Text(stringResource(UiR.string.remove)) }
                        },
                    )
                }
            }
        }
    }

    if (showEidQr && eid != null) {
        EidDialog(eid = eid, onDismiss = { showEidQr = false })
    }
}
