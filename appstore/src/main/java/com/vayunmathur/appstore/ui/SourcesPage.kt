package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.ModernAppsRepo
import com.vayunmathur.appstore.data.accrescent.AccrescentRepo
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text

/**
 * Which sources the store talks to at all.
 *
 * The source list is fixed — see [DefaultRepos] for why nothing can be added to it.
 */
@Composable
fun SourcesPage(
    viewModel: AppStoreViewModel,
    onOpenTrust: () -> Unit = {},
) {
    val home by viewModel.home.collectAsState()
    val autoInstallUpdates by viewModel.autoInstallUpdates.collectAsState()
    val enabledSources by viewModel.enabledSources.collectAsState()
    val noneEnabled = AppSource.TOGGLEABLE.none { it in enabledSources }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onOpenTrust, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.trust_page_title))
            }
            if (noneEnabled) {
                Text(
                    stringResource(R.string.sources_all_disabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (home.statusMessage.isNotBlank()) {
                Text(
                    home.statusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            Card(Modifier.fillMaxWidth()) {
                SettingsSwitchRow(
                    title = stringResource(R.string.setting_auto_install_updates_title),
                    supportingText = stringResource(R.string.setting_auto_install_updates_summary),
                    checked = autoInstallUpdates,
                    onCheckedChange = { viewModel.setAutoInstallUpdates(it) },
                )
            }

            SourceCard(
                title = stringResource(R.string.source_modern_apps),
                subtitle = ModernAppsRepo.PROJECT_URL,
                enabled = AppSource.MODERN_APPS in enabledSources,
                onEnabledChange = { viewModel.setSourceEnabled(AppSource.MODERN_APPS, it) },
            )
            SourceCard(
                title = stringResource(R.string.source_chip_fdroid),
                subtitle = DefaultRepos.FDROID.url,
                enabled = AppSource.FDROID in enabledSources,
                onEnabledChange = { viewModel.setSourceEnabled(AppSource.FDROID, it) },
            )
            SourceCard(
                title = stringResource(R.string.source_chip_izzy),
                subtitle = DefaultRepos.IZZY.url,
                enabled = AppSource.IZZY in enabledSources,
                onEnabledChange = { viewModel.setSourceEnabled(AppSource.IZZY, it) },
            )
            SourceCard(
                title = stringResource(R.string.source_chip_proprietary),
                subtitle = DefaultRepos.PROPRIETARY.url,
                enabled = AppSource.PROPRIETARY in enabledSources,
                onEnabledChange = { viewModel.setSourceEnabled(AppSource.PROPRIETARY, it) },
            )
            SourceCard(
                title = stringResource(R.string.source_play),
                subtitle = "play.google.com",
                enabled = AppSource.PLAYSTORE in enabledSources,
                onEnabledChange = { viewModel.setSourceEnabled(AppSource.PLAYSTORE, it) },
            )
            SourceCard(
                title = stringResource(R.string.source_chip_accrescent),
                subtitle = AccrescentRepo.REPOSITORY_URL,
                enabled = AppSource.ACCRESCENT in enabledSources,
                onEnabledChange = { viewModel.setSourceEnabled(AppSource.ACCRESCENT, it) },
            )
        }
    }
}

@Composable
private fun SourceCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}
