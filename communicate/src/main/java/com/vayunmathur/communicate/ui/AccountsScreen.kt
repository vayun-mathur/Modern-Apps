package com.vayunmathur.communicate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

/**
 * Account management for the merged inbox: shows the physical SIM line and the Google Voice
 * line, with sign-in / sign-out and the connected GV number.
 */
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onRegisterWhatsApp: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onRegisterSignal: () -> Unit = {},
    onShowRcsStatus: () -> Unit = {},
) {
    val context = LocalContext.current
    val signedOutMessage = stringResource(R.string.gv_signed_out)
    val session = remember { GoogleVoiceSession.get(context) }
    val waSession = remember { com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession.get(context) }
    val waSignedIn by waSession.signedInFlow.collectAsState(initial = false)
    val waNumber by waSession.phoneNumberFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val signedIn by session.signedInFlow.collectAsState(initial = false)
    val number by session.phoneNumberFlow.collectAsState(initial = null)

    // Fetch the GV number once after sign-in if we don't have it yet.
    androidx.compose.runtime.LaunchedEffect(signedIn, number) {
        if (signedIn && number == null) {
            runCatching { GoogleVoiceClient.get(context).getAccount() }
        }
    }

    DetailScaffold(
        title = stringResource(R.string.accounts_title),
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                leadingContent = { IconCall() },
                content = { Text(stringResource(R.string.account_sim), fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(stringResource(R.string.account_sim)) },
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                ListItem(
                    leadingContent = { IconPerson() },
                    content = {
                        Text(stringResource(R.string.account_google_voice), fontWeight = FontWeight.SemiBold)
                    },
                    supportingContent = {
                        Text(
                            if (signedIn) {
                                number ?: stringResource(R.string.gv_number_unknown)
                            } else {
                                stringResource(R.string.gv_not_signed_in)
                            },
                        )
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (signedIn) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    session.signOut()
                                    AppMessages.show(signedOutMessage)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.gv_sign_out))
                        }
                    } else {
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.gv_sign_in))
                        }
                    }
                }
            }
        }

        if (signedIn && number == null) {
            Text(
                stringResource(R.string.gv_number_unknown),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Signal primary line (dev-only; hidden in the release variant).
        if (com.vayunmathur.communicate.data.signal.SignalFeature.enabled) {
            val sigSession = remember { com.vayunmathur.communicate.data.signal.SignalLineSession.get(context) }
            val sigSignedIn by sigSession.signedInFlow.collectAsState(initial = false)
            val sigNumber by sigSession.phoneNumberFlow.collectAsState(initial = null)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    ListItem(
                        leadingContent = { IconPerson() },
                        content = { Text("Signal", fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text(if (sigSignedIn) (sigNumber ?: "Registered") else "Not registered")
                        },
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (sigSignedIn) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        sigSession.signOut(context)
                                        AppMessages.show("Signed out of Signal")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Sign out") }
                        } else {
                            Button(onClick = onRegisterSignal, modifier = Modifier.fillMaxWidth()) {
                                Text("Register")
                            }
                        }
                    }
                }
            }
        }

        // RCS single-registration line (dev-only; hidden in the release variant).
        if (com.vayunmathur.communicate.data.rcs.RcsFeature.enabled) {
            val rcsState by com.vayunmathur.communicate.data.rcs.RcsSipTransport.state.collectAsState(
                initial = RcsRegistrationState.Unknown,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    ListItem(
                        leadingContent = { IconPerson() },
                        content = { Text("RCS", fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text(
                                when (val s = rcsState) {
                                    is RcsRegistrationState.Available ->
                                        "Available"
                                    is RcsRegistrationState.Provisioning ->
                                        "Checking…"
                                    is RcsRegistrationState.Disabled -> "Disabled"
                                    is RcsRegistrationState.Unknown ->
                                        "Not checked"
                                    is RcsRegistrationState.Unavailable ->
                                        "Unavailable: ${s.reason}"
                                },
                            )
                        },
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onShowRcsStatus, modifier = Modifier.fillMaxWidth()) {
                            Text("Status")
                        }
                    }
                }
            }
        }

        // WhatsApp primary line (dev-only; hidden in the release variant).
        if (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    ListItem(
                        leadingContent = { IconPerson() },
                        content = { Text("WhatsApp", fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text(if (waSignedIn) (waNumber ?: "Registered") else "Not registered")
                        },
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (waSignedIn) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        waSession.signOut(context)
                                        AppMessages.show("Signed out of WhatsApp")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Sign out") }
                        } else {
                            Button(onClick = onRegisterWhatsApp, modifier = Modifier.fillMaxWidth()) {
                                Text("Register")
                            }
                        }
                        OutlinedButton(onClick = onImportBackup, modifier = Modifier.fillMaxWidth()) {
                            Text("Import backup (.crypt15)")
                        }
                    }
                }
            }
        }
    }
}
