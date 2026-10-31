package com.vayunmathur.appstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.TrustProfile
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.util.AppDetailActions
import com.vayunmathur.appstore.util.AppDetailUiState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * The primary action.
 *
 * There is exactly one full-width button and it always says what tapping it will do —
 * including mid-install, where it reports the stage instead of going dead and leaving a
 * separate progress bar to explain itself.
 */
@Composable
internal fun AppInstallActions(
    state: AppDetailUiState,
    actions: AppDetailActions,
    onUninstall: () -> Unit,
) {
    val app = state.app ?: return
    val stage = state.stage
    val busy = stage != null && stage !is InstallStage.Failed

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                busy -> Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                    Text(stageLabel(stage))
                }

                state.hasUpdate -> {
                    Button(onClick = { actions.install(app) }, modifier = Modifier.weight(1f)) {
                        IconDownload()
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_update))
                    }
                    FilledTonalButton(onClick = { actions.openApp(app.packageName) }) {
                        Text(stringResource(UiR.string.open))
                    }
                }

                state.isInstalled -> {
                    FilledTonalButton(
                        onClick = { actions.openApp(app.packageName) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(UiR.string.open))
                    }
                    OutlinedButton(onClick = onUninstall) { IconDelete() }
                }

                else -> Button(
                    onClick = { actions.install(app) },
                    modifier = Modifier.weight(1f),
                ) {
                    IconDownload()
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_install))
                }
            }
        }

        if (state.isInstalled && state.installedInfo?.versionName != null && !state.hasUpdate) {
            Text(
                stringResource(R.string.installed_2, state.installedInfo.versionName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        StageProgress(stage)
        if (stage is InstallStage.Failed) {
            TextButton(onClick = { actions.dismissInstallFailure(app.packageName) }) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    }
}

/**
 * What can be checked about this app's download, and what the last install proved.
 *
 * Neutral by design: the card describes the source's own practices and this app's checks,
 * and does not claim one source is safer than another. Tapping opens the full comparison.
 */
@Composable
internal fun AppTrustCard(
    source: AppSource,
    verification: VerificationResult?,
    onOpenTrust: () -> Unit,
) {
    val profile = TrustProfile.of(source)
    Card(
        onClick = onOpenTrust,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(profile.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(profile.summary), style = MaterialTheme.typography.bodySmall)
            when (verification) {
                is VerificationResult.Rejected -> Text(
                    stringResource(R.string.detail_install_blocked, verification.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                is VerificationResult.Verified -> Text(
                    stringResource(R.string.detail_checked_on_install, verification.detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is VerificationResult.Unverified -> Text(
                    stringResource(R.string.detail_installed_unverified, verification.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Text(
                    stringResource(R.string.detail_tap_for_trust),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun AppDetailLinks(app: UnifiedApp, actions: AppDetailActions) {
    val links = buildList<Pair<Int, String>> {
        app.website?.let { add(R.string.link_website to it) }
        app.sourceCode?.let { add(R.string.link_source to it) }
        app.privacyPolicyUrl?.let { add(R.string.link_privacy to it) }
    }
    if (links.isEmpty() && app.source != AppSource.PLAYSTORE) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        links.forEach { (label, url) ->
            OutlinedButton(
                onClick = { actions.openInBrowser(url) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconGlobe()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label))
            }
        }
        if (app.source == AppSource.PLAYSTORE) {
            OutlinedButton(
                onClick = { actions.openInPlayStore(app.packageName) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.view_in_play_store))
            }
        }
    }
}

/** The remaining facts, as a plain table — deliberately last. */
@Composable
internal fun AppDetailDetails(app: UnifiedApp) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HorizontalDivider()
        DetailRow(stringResource(R.string.detail_package), app.packageName)
        app.license?.let { DetailRow(stringResource(R.string.detail_license), it) }
        app.targetSdk?.let { DetailRow(stringResource(R.string.detail_target_sdk), it.toString()) }
        if (app.ratingCount > 0) {
            DetailRow(stringResource(R.string.detail_rating_count), formatCount(app.ratingCount))
        }
        if (app.permissions.isNotEmpty()) {
            DetailRow(
                stringResource(R.string.detail_permissions),
                app.permissions.joinToString("\n") { it.substringAfterLast('.') },
            )
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
