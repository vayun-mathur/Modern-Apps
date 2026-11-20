package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.R
import com.vayunmathur.securemail.platform.MailUiState

/** Biometric gate: the PGP private key stays wrapped until this succeeds. */
@Composable
fun LockScreen(
    state: MailUiState,
    onUnlock: () -> Unit,
    onDismissMessages: () -> Unit,
) {
    val messenger = rememberMessenger()
    ConsumeMessage(state.message, messenger, onDismissMessages)
    AppScaffold(title = stringResource(R.string.lock_title)) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.lock_unlock_to_read), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.lock_pgp_explainer),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { onDismissMessages(); onUnlock() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.lock_unlock))
                        }
                    }
                }
            }
        }
    }
}
