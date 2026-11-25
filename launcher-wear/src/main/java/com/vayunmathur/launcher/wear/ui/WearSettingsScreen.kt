package com.vayunmathur.launcher.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.launcher.wear.R
import com.vayunmathur.launcher.wear.platform.InstalledApps

/**
 * Settings shortcuts: only system intents that resolve on this watch.
 * No brightness/sound toggles are faked here - each row opens the system
 * screen that actually owns the setting.
 */
@Composable
fun WearSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val targets = remember(context) { InstalledApps.settingsTargets(context) }
    WearSettingsContent(
        targets = targets,
        onOpenTarget = { target -> runCatching { context.startActivity(InstalledApps.settingsIntent(target)) } },
        onBack = onBack,
    )
}

/**
 * Stateless settings behind [WearSettingsScreen]: targets are a parameter, so Compose
 * previews (src/screenshotTest) render literal fixtures without probing PackageManager.
 */
@Composable
internal fun WearSettingsContent(
    targets: List<com.vayunmathur.launcher.wear.domain.WearSettingsTarget>,
    onOpenTarget: (com.vayunmathur.launcher.wear.domain.WearSettingsTarget) -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.wear_settings),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (targets.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_settings_unavailable),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(targets, key = { it.action }) { target ->
                Chip(
                    onClick = { onOpenTarget(target) },
                    label = { Text(target.label, maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        item {
            Chip(
                onClick = onBack,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
