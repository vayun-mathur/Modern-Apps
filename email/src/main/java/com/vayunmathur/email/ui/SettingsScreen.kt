package com.vayunmathur.email.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: EmailViewModel, onBack: () -> Unit) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val resources = LocalResources.current
    DetailScaffold(
        title = stringResource(UiR.string.settings),
        onNavigateBack = onBack,
    ) {
        val settings = remember(context) { com.vayunmathur.email.data.EmailSettings.get(context) }
        val loadRemoteImages by settings.loadRemoteImages.collectAsStateWithLifecycle()
        Text(stringResource(R.string.reading), style = MaterialTheme.typography.titleMedium)
        com.vayunmathur.library.ui.SettingsSwitchRow(
            title = stringResource(R.string.load_remote_images),
            supportingText = stringResource(R.string.load_remote_images_summary),
            checked = loadRemoteImages,
            onCheckedChange = { settings.setLoadRemoteImages(it) },
        )
        HorizontalDivider()

        Text(stringResource(R.string.signatures), style = MaterialTheme.typography.titleMedium)
        if (accounts.isEmpty()) {
            Text(stringResource(R.string.select_account))
        }
        accounts.forEach { acc ->
            var sig by remember(acc.email) { mutableStateOf(acc.signature) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(acc.email, style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = sig,
                    onValueChange = { sig = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = { Text(stringResource(R.string.your_signature)) },
                )
                Button(
                    onClick = {
                        viewModel.setSignature(acc.email, sig)
                        AppMessages.show(resources.getString(R.string.signature_saved))
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(UiR.string.save))
                }
                com.vayunmathur.library.ui.SettingsSwitchRow(
                    title = stringResource(R.string.allow_insecure_certificates),
                    supportingText = stringResource(R.string.allow_insecure_certificates_summary),
                    checked = acc.allowInsecureCertificates,
                    onCheckedChange = { viewModel.setAllowInsecureCertificates(acc.email, it) },
                )
            }
            HorizontalDivider()
        }

        val blocked by viewModel.blockedSenders.collectAsStateWithLifecycle(emptyList())
        Text(stringResource(R.string.blocked_senders), style = MaterialTheme.typography.titleMedium)
        if (blocked.isEmpty()) {
            Text(stringResource(R.string.none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        blocked.forEach { b ->
            ListItem(
                content = { Text(b.address) },
                trailingContent = {
                    TextButton(onClick = { viewModel.unblockSender(b.address) }) { Text(stringResource(R.string.unblock)) }
                },
            )
            HorizontalDivider()
        }
    }
}
