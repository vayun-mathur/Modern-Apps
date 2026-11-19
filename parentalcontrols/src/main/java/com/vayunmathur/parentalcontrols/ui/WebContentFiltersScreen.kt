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
import com.vayunmathur.parentalcontrols.platform.WebContentFilters

/**
 * The single-toggle web-filter editor.
 *
 * [enabled]/[onEnabledChange] are hoisted so screenshot previews can drive the screen with
 * literals; when left null the screen owns its state and persists it via [WebContentFilters],
 * which is how the host route uses it.
 */
@Composable
fun WebContentFiltersScreen(
    enabled: Boolean? = null,
    onEnabledChange: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    var stored by remember { mutableStateOf(WebContentFilters.isEnabled(context)) }
    val checked = enabled ?: stored
    val onChange: (Boolean) -> Unit = onEnabledChange ?: {
        stored = it
        WebContentFilters.setEnabled(context, it)
    }

    LazyListScaffold(
        title = stringResource(R.string.web_filters_title),
        horizontalPadding = 0.dp,
    ) {
        item {
            SettingsSection {
                SettingsSwitchRow(
                    title = stringResource(R.string.web_filters_toggle),
                    supportingText = stringResource(R.string.web_filters_toggle_hint),
                    checked = checked,
                    onCheckedChange = onChange,
                )
            }
        }
    }
}
