package com.vayunmathur.appstore.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.ModernAppsRepo
import com.vayunmathur.appstore.data.accrescent.AccrescentRepo
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvSwitchRow
import com.vayunmathur.library.tv.tvFullWidth

/**
 * Which sources the store talks to at all. Mirrors the phone's Sources page: the
 * auto-install switch plus one switch row per source, in the same order. The source
 * list is fixed — see DefaultRepos for why nothing can be added to it.
 */
@Composable
fun TvStoreSources(
    viewModel: TvStoreViewModel,
    modifier: Modifier = Modifier,
) {
    val enabledSources by viewModel.enabledSourcesFlow.collectAsState()
    val autoInstallUpdates by viewModel.autoInstallUpdates.collectAsState()
    TvStoreSourcesScreen(
        enabledSources = enabledSources,
        autoInstallUpdates = autoInstallUpdates,
        onSetSourceEnabled = viewModel::setSourceEnabled,
        onSetAutoInstall = viewModel::setAutoInstallUpdates,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreSourcesScreen(
    enabledSources: Set<AppSource>,
    autoInstallUpdates: Boolean,
    modifier: Modifier = Modifier,
    onSetSourceEnabled: (AppSource, Boolean) -> Unit = { _, _ -> },
    onSetAutoInstall: (Boolean) -> Unit = {},
) {
    val noneEnabled = AppSource.TOGGLEABLE.none { it in enabledSources }
    TvGrid(columns = 1, modifier = modifier.fillMaxSize()) {
        tvFullWidth(key = "header") {
            TvScreenHeader(title = stringResource(R.string.nav_sources))
        }
        if (noneEnabled) {
            tvFullWidth(key = "none") {
                Text(
                    text = stringResource(R.string.sources_all_disabled),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        tvFullWidth(key = "auto") {
            TvSwitchRow(
                headline = stringResource(R.string.setting_auto_install_updates_title),
                supporting = stringResource(R.string.setting_auto_install_updates_summary),
                checked = autoInstallUpdates,
                onCheckedChange = onSetAutoInstall,
            )
        }
        for (source in SOURCES_IN_ORDER) {
            tvFullWidth(key = "source-${source.source.name}") {
                TvSwitchRow(
                    headline = stringResource(source.titleRes),
                    supporting = source.subtitle,
                    checked = source.source in enabledSources,
                    onCheckedChange = { onSetSourceEnabled(source.source, it) },
                )
            }
        }
    }
}

/** The phone's Sources page order, with the same titles and subtitles. */
private data class TvSourceRow(
    val source: AppSource,
    val titleRes: Int,
    val subtitle: String,
)

private val SOURCES_IN_ORDER = listOf(
    TvSourceRow(AppSource.MODERN_APPS, R.string.source_modern_apps, ModernAppsRepo.PROJECT_URL),
    TvSourceRow(AppSource.FDROID, R.string.source_chip_fdroid, DefaultRepos.FDROID.url),
    TvSourceRow(AppSource.IZZY, R.string.source_chip_izzy, DefaultRepos.IZZY.url),
    TvSourceRow(
        AppSource.PROPRIETARY,
        R.string.source_chip_proprietary,
        DefaultRepos.PROPRIETARY.url,
    ),
    TvSourceRow(AppSource.PLAYSTORE, R.string.source_play, "play.google.com"),
    TvSourceRow(
        AppSource.ACCRESCENT,
        R.string.source_chip_accrescent,
        AccrescentRepo.REPOSITORY_URL,
    ),
)
