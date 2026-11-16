package com.vayunmathur.email.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.R
import com.vayunmathur.email.data.PROVIDER_CUSTOM
import com.vayunmathur.email.data.PROVIDER_OUTLOOK
import com.vayunmathur.email.data.PROVIDER_PRESETS
import com.vayunmathur.email.data.ProviderPreset

/**
 * Add-account provider picker (`Route.AddAccount`): picking a provider
 * pushes `Route.AddAccountForm(providerId)`, so Back from the form is a
 * plain stack pop to this picker — no in-screen step state or hand-rolled
 * BackHandler. The forms themselves live in [AddAccountFormScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    onBack: (() -> Unit)?,
    onPickProvider: (String) -> Unit,
) {
    AppScaffold(
        title = stringResource(R.string.add_account),
        navigationIcon = {
            if (onBack != null) { IconNavigation(onBack) }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            ProviderPicker(onPick = { onPickProvider(it.id) })
        }
    }
}

@Composable
private fun ProviderPicker(onPick: (ProviderPreset) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(stringResource(R.string.choose_your_email_provider), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
        PROVIDER_PRESETS.forEach { preset ->
            ElevatedCard(onClick = { onPick(preset) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(preset.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            preset.id == PROVIDER_CUSTOM -> stringResource(R.string.enter_imap_smtp_server_details_manually)
                            preset.id == PROVIDER_OUTLOOK -> stringResource(R.string.oauth_sign_in)
                            else -> stringResource(R.string.app_password)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
