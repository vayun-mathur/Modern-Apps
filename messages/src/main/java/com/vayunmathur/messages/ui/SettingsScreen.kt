package com.vayunmathur.messages.ui

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.vayunmathur.messages.util.MessagesSessionManager
import com.vayunmathur.messages.util.MessagesViewModel
import com.vayunmathur.messages.util.SourceConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backStack: NavBackStack<Route>,
    vm: MessagesViewModel,
) {
    val states by vm.connectionStates.collectAsState()
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
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { vm.forceResync() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconRestore(Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_resync))
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
