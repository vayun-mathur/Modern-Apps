package com.vayunmathur.messages.ui

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gvoice.GVoiceClient
import com.vayunmathur.messages.util.MessagesSessionManager
import com.vayunmathur.messages.util.MessagesViewModel
import com.vayunmathur.messages.util.SourceConnectionState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backStack: NavBackStack<Route>,
    vm: MessagesViewModel,
) {
    val states by vm.connectionStates.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Account picker launcher for OAuth token access
    val accountPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (accountName != null) {
                // Store selected account for OAuth token fetching
                val prefs = context.getSharedPreferences("gvoice_oauth", Context.MODE_PRIVATE)
                prefs.edit().putString("selected_account", accountName).apply()
                android.util.Log.i("SettingsScreen", "Selected Google account: $accountName")
                // Now run the SIP test with the selected account
                scope.launch {
                    vm.testSIPRegisterInfo { result ->
                        android.util.Log.i("SIP_TEST", "=== SIP TEST RESULT ===\n$result")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Connect to these services at your own risk. Account bans are very " +
                        "rare but technically possible. Messages on E2EE platforms maintain " +
                        "E2EE on this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_section_sources),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SourceCard(
                title = stringResource(R.string.source_messages),
                state = states[MessageSource.MESSAGES_WEB] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.PairMessages) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.MESSAGES_WEB) },
            )
            Spacer(Modifier.height(8.dp))
            SourceCard(
                title = stringResource(R.string.source_voice),
                state = states[MessageSource.VOICE] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.LoginVoice) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.VOICE) },
            )
            Spacer(Modifier.height(8.dp))
            SourceCard(
                title = stringResource(R.string.source_telegram),
                state = states[MessageSource.TELEGRAM] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.LoginTelegram) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.TELEGRAM) },
            )
            Spacer(Modifier.height(8.dp))
            SourceCard(
                title = stringResource(R.string.source_signal),
                state = states[MessageSource.SIGNAL] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.LoginSignal) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.SIGNAL) },
            )
            Spacer(Modifier.height(8.dp))
            SourceCard(
                title = "WhatsApp",
                state = states[MessageSource.WHATSAPP] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.LoginWhatsApp) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.WHATSAPP) },
            )
            Spacer(Modifier.height(8.dp))
            SourceCard(
                title = "Messenger",
                state = states[MessageSource.MESSENGER] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.LoginMessenger) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.MESSENGER) },
            )
            Spacer(Modifier.height(8.dp))
            SourceCard(
                title = "Instagram",
                state = states[MessageSource.INSTAGRAM] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.LoginInstagram) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.INSTAGRAM) },
            )
            Spacer(Modifier.height(8.dp))
            SourceCard(
                title = "RCS",
                state = states[MessageSource.RCS] ?: SourceConnectionState.Idle,
                onConfigure = { backStack.add(Route.LoginRcs) },
                onDisconnect = { MessagesSessionManager.stop(MessageSource.RCS) },
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { vm.forceResync() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconRestore(Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_resync))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    // Check if account already selected, otherwise launch picker
                    val prefs = context.getSharedPreferences("gvoice_oauth", Context.MODE_PRIVATE)
                    val savedAccount = prefs.getString("selected_account", null)
                    if (savedAccount == null) {
                        // Launch account picker to grant app access to Google account
                        val intent = AccountManager.newChooseAccountIntent(
                            null, null, arrayOf("com.google"), null, null, null, null
                        )
                        accountPickerLauncher.launch(intent)
                    } else {
                        // Account already selected, run SIP test directly
                        vm.testSIPRegisterInfo { result ->
                            android.util.Log.i("SIP_TEST", "=== SIP TEST RESULT ===\n$result")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test SIP Register Info (Phase 1)")
            }
        }
    }
}

@Composable
private fun SourceCard(
    title: String,
    state: SourceConnectionState,
    onConfigure: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val isConnected = state == SourceConnectionState.Connected
    val containerColor by animateColorAsState(
        if (isConnected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "cardColor",
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    describe(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text("Disconnect")
                }
            } else {
                FilledTonalButton(onClick = onConfigure) {
                    Text("Set up")
                }
            }
        }
    }
}

private fun describe(state: SourceConnectionState): String = when (state) {
    SourceConnectionState.Idle -> "Not set up"
    is SourceConnectionState.NeedsSetup -> "Setup required"
    is SourceConnectionState.Pairing -> "Waiting for QR scan…"
    SourceConnectionState.Connecting -> "Connecting…"
    SourceConnectionState.Connected -> "Connected"
    is SourceConnectionState.Disconnected -> "Disconnected: ${state.reason}"
}
