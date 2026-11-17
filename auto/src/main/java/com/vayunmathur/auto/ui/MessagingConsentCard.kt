package com.vayunmathur.auto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.MessagingPrefs
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.SpecialAccess
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Gates message mirroring on two switches: the in-app consent and the system
 * notification-listener binding. Both must be on before a notification reaches
 * the car; either off means fail-closed silence. Navigation failures report on
 * the shared snackbar host -- never a Toast.
 */
@Composable
fun MessagingConsentCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val listenerMissingMessage = stringResource(R.string.messaging_listener_missing)
    var listenerGranted by remember { mutableStateOf(SpecialAccess.hasNotificationListener(context)) }
    var consented by remember { mutableStateOf(MessagingPrefs.isMirroringConsented(context)) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SettingsSection(title = stringResource(R.string.messaging_headline)) {
                Text(
                    text = stringResource(R.string.messaging_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.messaging_consent),
                    checked = consented,
                    onCheckedChange = {
                        consented = it
                        MessagingPrefs.setMirroringConsented(context, it)
                        // Re-read the system gate: the user may have revoked it
                        // in settings since this screen was composed.
                        listenerGranted = SpecialAccess.hasNotificationListener(context)
                    },
                    supportingText = stringResource(
                        if (consented) R.string.messaging_consent_on
                        else R.string.messaging_consent_off,
                    ),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.messaging_listener)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (listenerGranted) R.string.messaging_listener_granted
                                else R.string.messaging_listener_missing,
                            ),
                        )
                    },
                    trailingContent = {
                        Button(onClick = {
                            runCatching {
                                SpecialAccess.requestNotificationListener(context)
                            }.onFailure {
                                messenger.show(listenerMissingMessage)
                            }
                        }) {
                            Text(stringResource(R.string.messaging_open_settings))
                        }
                    },
                )
            }
        }
    }
}
