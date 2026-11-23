package com.vayunmathur.parentalcontrols.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.parentalcontrols.R
import com.vayunmathur.parentalcontrols.platform.SupervisionAvailability
import com.vayunmathur.parentalcontrols.platform.WebContentFilters

/**
 * The single-toggle web-filter editor.
 *
 * [enabled]/[onEnabledChange] are hoisted so screenshot previews can drive the screen with
 * literals; when left null the screen owns its state and persists it via [WebContentFilters],
 * which is how the host route uses it.
 *
 * There is no enforcement hook behind this toggle yet (see [WebContentFilters]), so the
 * supporting text says so instead of presenting a working filter: on a device without
 * supervision it names the supervised tier this belongs to, and on a supervised device
 * it still says the setting is saved but not enforced. The toggle stays usable so the
 * preference can be pre-configured; it just never silently pretends to filter.
 */
@Composable
fun WebContentFiltersScreen(
    enabled: Boolean? = null,
    onEnabledChange: ((Boolean) -> Unit)? = null,
    enforcementAvailable: Boolean? = null,
) {
    val context = LocalContext.current
    var stored by remember { mutableStateOf(WebContentFilters.isEnabled(context)) }
    val checked = enabled ?: stored
    val onChange: (Boolean) -> Unit = onEnabledChange ?: {
        stored = it
        WebContentFilters.setEnabled(context, it)
    }
    val supervised = enforcementAvailable ?: SupervisionAvailability.isAvailable(context)

    LazyListScaffold(
        title = stringResource(R.string.web_filters_title),
        horizontalPadding = 0.dp,
    ) {
        item {
            SettingsSection {
                SettingsSwitchRow(
                    title = stringResource(R.string.web_filters_toggle),
                    supportingText = stringResource(
                        if (supervised) {
                            R.string.web_filters_not_yet_enforced
                        } else {
                            R.string.web_filters_supervision_only
                        },
                    ),
                    checked = checked,
                    onCheckedChange = onChange,
                )
            }
        }
    }
}
