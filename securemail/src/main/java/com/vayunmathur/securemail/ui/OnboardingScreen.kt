package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.platform.MailUiState

/** First-run: claim an @maemail.org address and generate the PGP identity. */
@Composable
fun OnboardingScreen(
    state: MailUiState,
    onClaim: (String, String) -> Unit,
    onDismissMessages: () -> Unit,
) {
    val messenger = rememberMessenger()
    ConsumeMessage(state.message, messenger, onDismissMessages)
    AppScaffold(title = "Set up SecureMail") { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "Claim an @maemail.org address. A PGP key (Ed25519 + X25519) is " +
                        "generated on this device — the private key never leaves it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ClaimCard(
                    busy = state.busy,
                    onClaim = { local, pass -> onDismissMessages(); onClaim(local, pass) },
                )
            }
        }
    }
}

@Composable
private fun ClaimCard(busy: Boolean, onClaim: (String, String) -> Unit) {
    var local by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Your address", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = local,
                onValueChange = { local = it },
                label = { Text("name (→ name@maemail.org)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Key passphrase (min 8 chars)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                onClick = { onClaim(local.trim(), passphrase) },
                enabled = !busy && local.isNotBlank() && passphrase.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Generating…" else "Claim address + generate key") }
        }
    }
}
