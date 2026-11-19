package com.vayunmathur.web.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.R
import com.vayunmathur.web.Route
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldLevel
import com.vayunmathur.web.domain.ShieldsSettings
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.clearSiteShields
import com.vayunmathur.web.platform.updateShields
import com.vayunmathur.web.platform.updateSiteShields

/**
 * Brave's shields panel: the shield in the toolbar opens this for the current site.
 *
 * Changes are stored per host and take effect for new requests immediately, but the page
 * already rendered keeps whatever was injected into it, hence the reload affordance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldsPanel(
    host: String,
    blockedCount: Int,
    viewModel: WebViewModel,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Read through the Room-backed flow, not the render-thread mirror, so the panel
    // recomposes as soon as a change lands.
    val overrides by viewModel.shieldSettings.collectAsStateWithLifecycle()
    val site = overrides.firstOrNull { it.host == host }?.toSettings()
    val effective = EffectiveShields.resolve(viewModel.shields, site)

    fun update(transform: (ShieldsSettings) -> ShieldsSettings) {
        viewModel.updateSiteShields(host, transform(site ?: ShieldsSettings()))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.shields_for_host, host), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (effective.level == ShieldLevel.OFF) {
                        stringResource(R.string.shields_down)
                    } else {
                        pluralStringResource(R.plurals.shields_blocked_count, blockedCount, blockedCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            ShieldLevel.entries.forEach { level ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { update { it.copy(level = level) } }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = effective.level == level,
                        onClick = { update { it.copy(level = level) } },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(level.titleRes), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(level.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (effective.level != ShieldLevel.OFF) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ShieldToggle(
                    R.string.shields_block_trackers,
                    R.string.shields_block_trackers_desc,
                    effective.blockTrackers,
                ) { on -> update { it.copy(blockTrackers = on) } }
                ShieldToggle(
                    R.string.shields_cosmetic,
                    R.string.shields_cosmetic_desc,
                    effective.cosmeticFiltering,
                ) { on -> update { it.copy(cosmeticFiltering = on) } }
                ShieldToggle(
                    R.string.shields_fingerprint,
                    R.string.shields_fingerprint_desc,
                    effective.fingerprintProtection,
                ) { on -> update { it.copy(fingerprintProtection = on) } }
                ShieldToggle(
                    R.string.shields_https,
                    R.string.shields_https_desc,
                    effective.httpsUpgrade,
                ) { on -> update { it.copy(httpsUpgrade = on) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { viewModel.updateSiteShields(host, ShieldsSettings()) }) {
                    Text(stringResource(R.string.shields_reset_site))
                }
                TextButton(onClick = { onReload(); onDismiss() }) {
                    Text(stringResource(R.string.shields_reload_to_apply))
                }
            }
        }
    }
}

@Composable
private fun ShieldToggle(
    titleRes: Int,
    descriptionRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(titleRes)) },
        supportingContent = { Text(stringResource(descriptionRes)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

private val ShieldLevel.titleRes: Int
    get() = when (this) {
        ShieldLevel.OFF -> UiR.string.off
        ShieldLevel.STANDARD -> R.string.shields_level_standard
        ShieldLevel.AGGRESSIVE -> R.string.shields_level_aggressive
    }

private val ShieldLevel.descriptionRes: Int
    get() = when (this) {
        ShieldLevel.OFF -> R.string.shields_level_off_desc
        ShieldLevel.STANDARD -> R.string.shields_level_standard_desc
        ShieldLevel.AGGRESSIVE -> R.string.shields_level_aggressive_desc
    }

/** Global shield defaults plus the list of sites that deviate from them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShieldsPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val overrides by viewModel.shieldSettings.collectAsStateWithLifecycle()
    val global = viewModel.shields

    AppScaffold(title = stringResource(R.string.shields), backStack = backStack) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Text(
                    stringResource(R.string.shields_level),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(ShieldLevel.entries) { level ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.updateShields(global.copy(level = level)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = global.level == level,
                        onClick = { viewModel.updateShields(global.copy(level = level)) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(level.titleRes), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(level.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item {
                ShieldToggle(
                    R.string.shields_block_trackers,
                    R.string.shields_block_trackers_desc,
                    global.blockTrackers != false,
                ) { on -> viewModel.updateShields(global.copy(blockTrackers = on)) }
            }
            item {
                ShieldToggle(
                    R.string.shields_cosmetic,
                    R.string.shields_cosmetic_desc,
                    global.cosmeticFiltering != false,
                ) { on -> viewModel.updateShields(global.copy(cosmeticFiltering = on)) }
            }
            item {
                ShieldToggle(
                    R.string.shields_fingerprint,
                    R.string.shields_fingerprint_desc,
                    global.fingerprintProtection != false,
                ) { on -> viewModel.updateShields(global.copy(fingerprintProtection = on)) }
            }
            item {
                ShieldToggle(
                    R.string.shields_https,
                    R.string.shields_https_desc,
                    global.httpsUpgrade != false,
                ) { on -> viewModel.updateShields(global.copy(httpsUpgrade = on)) }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item {
                Text(
                    stringResource(R.string.shields_site_exceptions),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            if (overrides.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.shields_no_site_exceptions),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            } else {
                items(overrides, key = { it.host }) { override ->
                    ListItem(
                        headlineContent = { Text(override.host) },
                        supportingContent = {
                            Text(stringResource(EffectiveShields.resolve(global, override.toSettings()).level.titleRes))
                        },
                        modifier = Modifier.clickable {
                            viewModel.updateSiteShields(override.host, ShieldsSettings())
                        },
                    )
                }
                item {
                    TextButton(
                        onClick = { viewModel.clearSiteShields() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text(stringResource(R.string.shields_clear_exceptions)) }
                }
            }
        }
    }
}
