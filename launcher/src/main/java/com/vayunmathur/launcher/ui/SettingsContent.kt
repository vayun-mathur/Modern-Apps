package com.vayunmathur.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.launcher.R
import com.vayunmathur.launcher.platform.LauncherViewModel
import com.vayunmathur.launcher.platform.SettingsActions
import com.vayunmathur.launcher.platform.SettingsUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.IconGrid
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconWallpaper
import com.vayunmathur.library.ui.IconWidgets
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSelectRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * Launcher settings, and the only route to the wallpaper chooser and the widget picker.
 *
 * Also what a long press on empty wallpaper opens, which is why Wallpaper and Widgets are the
 * first two rows rather than buried under the grid options.
 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit = {},
    onOpenWidgetPicker: () -> Unit = {},
) {
    AppScaffold(title = "Home screen", onNavigateBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection {
                SettingsRow(
                    title = "Wallpaper",
                    supportingText = "Choose a wallpaper",
                    leadingContent = { IconWallpaper() },
                    onClick = actions::pickWallpaper,
                )
                SettingsRow(
                    title = "Widgets",
                    supportingText = "Add a widget to the home screen",
                    leadingContent = { IconWidgets() },
                    onClick = onOpenWidgetPicker,
                )
                if (!state.isDefaultHome) {
                    SettingsRow(
                        title = "Set as default home app",
                        supportingText = "Needed for shortcuts, widgets and the wallpaper",
                        leadingContent = { IconHome() },
                        onClick = actions::requestDefaultHome,
                    )
                }
            }

            SettingsDivider()

            SettingsSection(title = "Grid") {
                SettingsSelectRow(
                    title = "Columns",
                    selected = state.columns,
                    options = LauncherViewModel.COLUMN_OPTIONS,
                    label = { it.toString() },
                    onSelect = actions::setColumns,
                    leadingContent = { IconGrid() },
                )
                SettingsSelectRow(
                    title = "Rows",
                    selected = state.rows,
                    options = LauncherViewModel.ROW_OPTIONS,
                    label = { it.toString() },
                    onSelect = actions::setRows,
                )
                SettingsSelectRow(
                    title = "Favourites row",
                    supportingText = "Icons in the row at the bottom",
                    selected = state.hotseatSlots,
                    options = LauncherViewModel.COLUMN_OPTIONS,
                    label = { it.toString() },
                    onSelect = actions::setHotseatSlots,
                )
            }

            SettingsDivider()

            SettingsSection(title = "Icons") {
                SettingsSwitchRow(
                    title = "Show labels",
                    supportingText = "App names under icons on the home screen",
                    checked = state.showLabels,
                    onCheckedChange = actions::setShowLabels,
                    leadingContent = { IconApps() },
                )
                SettingsRow(
                    title = "Icon size",
                    supportingText = "${(state.iconScale * 100).toInt()}%",
                )
                Slider(
                    value = state.iconScale,
                    onValueChange = actions::setIconScale,
                    valueRange = LauncherViewModel.MIN_ICON_SCALE..LauncherViewModel.MAX_ICON_SCALE,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }

            SettingsDivider()

            SettingsSection(title = stringResource(R.string.settings_app_drawer)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_drawer_list_title),
                    supportingText = stringResource(R.string.settings_drawer_list_summary),
                    checked = state.drawerListLayout,
                    onCheckedChange = actions::setDrawerListLayout,
                    leadingContent = { IconList() },
                )
            }
        }
    }
}
