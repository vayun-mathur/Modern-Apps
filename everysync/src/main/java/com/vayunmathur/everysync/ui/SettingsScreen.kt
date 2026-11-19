package com.vayunmathur.everysync.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.data.Settings
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.everysync.platform.SettingsActions
import com.vayunmathur.everysync.platform.SettingsUiState
import com.vayunmathur.library.util.NavBackStack

/** Binds [EverySyncViewModel] and the back stack to the stateless [SettingsScreen]. */
@Composable
fun SettingsScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel) {
    val context = LocalContext.current
    val interval by viewModel.interval.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    // The conflict policy is a plain stored value rather than a flow, so the current
    // choice is held here and echoed back into the state on every change.
    var conflict by remember { mutableStateOf(Settings.conflictPolicy(context)) }
    val actions = remember(viewModel, backStack) {
        object : SettingsActions {
            override fun setInterval(minutes: Long) = viewModel.setInterval(minutes)

            override fun setWifiOnly(value: Boolean) = viewModel.setWifiOnly(value)

            override fun setConflictPolicy(policy: String) {
                conflict = policy
                viewModel.setConflictPolicy(policy)
            }

            override fun back() = backStack.pop()
        }
    }

    SettingsScreen(
        state = SettingsUiState(intervalMinutes = interval, wifiOnly = wifiOnly, conflictPolicy = conflict),
        actions = actions,
    )
}

/**
 * The settings screen, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions) {
    var intervalText by remember(state.intervalMinutes) { mutableStateOf(state.intervalMinutes.toString()) }

    DetailScaffold(
        title = stringResource(R.string.settings_title),
        onNavigateBack = { actions.back() },
    ) {
        OutlinedTextField(
            value = intervalText,
            onValueChange = {
                intervalText = it.filter { c -> c.isDigit() }
                intervalText.toLongOrNull()?.let { m -> actions.setInterval(m) }
            },
            label = { Text(stringResource(R.string.global_interval)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsSwitchRow(
            title = stringResource(R.string.wifi_only),
            checked = state.wifiOnly,
            onCheckedChange = { actions.setWifiOnly(it) },
        )
        HorizontalDivider()
        Text(
            stringResource(R.string.conflict_policy),
        )
        ConflictOption(R.string.conflict_lww, Settings.CONFLICT_LWW, state.conflictPolicy) { actions.setConflictPolicy(it) }
        ConflictOption(R.string.conflict_remote, Settings.CONFLICT_REMOTE, state.conflictPolicy) { actions.setConflictPolicy(it) }
        ConflictOption(R.string.conflict_local, Settings.CONFLICT_LOCAL, state.conflictPolicy) { actions.setConflictPolicy(it) }
    }
}

@Composable
private fun ConflictOption(labelRes: Int, value: String, selected: String, onSelect: (String) -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected == value, onClick = { onSelect(value) }),
        content = { Text(stringResource(labelRes)) },
        leadingContent = { RadioButton(selected = selected == value, onClick = { onSelect(value) }) },
    )
}
