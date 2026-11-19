package com.vayunmathur.maps.ui.settings
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.platform.formatDistance
import com.vayunmathur.maps.platform.isImperialUnits
import com.vayunmathur.maps.service.NavigationService

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconClearNight
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSelectRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.ThemeMode
import com.vayunmathur.maps.platform.MapOverlayWorker
import com.vayunmathur.maps.platform.MapSettingsViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * Maps settings screen (P6): map theme / day-night and the voice-guidance
 * toggle, plus an entry point to the saved-places screen. Built entirely from
 * the shared `library/ui` settings components (no raw Scaffold) and backed by
 * [MapSettingsViewModel] (DataStore).
 *
 * Distance/speed units are NOT a setting here — they follow the device's
 * regional settings via [com.vayunmathur.maps.platform.formatDistance] /
 * [com.vayunmathur.maps.platform.isImperialUnits].
 *
 * Wiring of the persisted values into behaviour lives at the point of use:
 *  - theme → `MainActivity` passes it to `DynamicTheme`,
 *  - voice guidance → `NavigationService` gates TTS on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSettingsPage(backStack: NavBackStack<Route>, viewModel: MapSettingsViewModel) {
    val theme by viewModel.themeMode.collectAsState()
    val voice by viewModel.voiceGuidance.collectAsState()
    val globe by viewModel.globeEnabled.collectAsState()
    val googleServices by viewModel.googleServices.collectAsState()
    val autoUpdate by viewModel.mapAutoUpdate.collectAsState()
    val appliedSeq by viewModel.overlayAppliedSeq.collectAsState()
    val pendingBytes by viewModel.overlayPendingBytes.collectAsState()
    val lastCheck by viewModel.overlayLastCheck.collectAsState()
    val context = LocalContext.current

    // Pre-resolve option labels — the SettingsSelectRow `label` lambda is a plain
    // (non-@Composable) function, so stringResource can't be called inside it.
    val themeLabels = mapOf(
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )

    AppScaffold(
        title = stringResource(UiR.string.settings),
        backStack = backStack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_display)) {
                SettingsSelectRow(
                    title = stringResource(R.string.settings_theme),
                    selected = theme,
                    options = ThemeMode.entries,
                    label = { themeLabels.getValue(it) },
                    onSelect = { viewModel.setThemeMode(it) },
                    leadingContent = { IconClearNight() },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_globe),
                    supportingText = stringResource(R.string.settings_globe_desc),
                    checked = globe,
                    onCheckedChange = { viewModel.setGlobeEnabled(it) },
                    leadingContent = { IconStar() },
                )
            }

            SettingsSection(title = stringResource(R.string.nav_channel_name)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_voice_guidance),
                    supportingText = stringResource(R.string.settings_voice_guidance_desc),
                    checked = voice,
                    onCheckedChange = { viewModel.setVoiceGuidance(it) },
                    leadingContent = { IconVolumeUp() },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_places)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_google_services),
                    supportingText = stringResource(R.string.settings_google_services_desc),
                    checked = googleServices,
                    onCheckedChange = { viewModel.setGoogleServices(it) },
                    leadingContent = { IconStar() },
                )
                com.vayunmathur.library.ui.SettingsRow(
                    title = stringResource(R.string.saved_places_title),
                    onClick = {
                        // Re-tap replaces the detail instead of stacking it, so the
                        // saved-places entry never piles up in two-pane layouts.
                        if (backStack.last() is Route.SavedPlacesPage) backStack.setLast(Route.SavedPlacesPage)
                        else backStack.add(Route.SavedPlacesPage)
                    },
                    leadingContent = { IconStar() },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_map_data)) {
                com.vayunmathur.library.ui.SettingsRow(
                    title = stringResource(R.string.settings_map_update),
                    supportingText = updateSupportingText(appliedSeq, pendingBytes, lastCheck),
                    onClick = { MapOverlayWorker.checkManual(context) },
                    leadingContent = { IconStar() },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_map_auto_update),
                    supportingText = stringResource(R.string.settings_map_auto_update_desc),
                    checked = autoUpdate,
                    onCheckedChange = { viewModel.setMapAutoUpdate(it) },
                    leadingContent = { IconStar() },
                )
            }
        }
    }
}

/**
 * One-line overlay status: applied sequence, pending bytes, or last check.
 * Formatting lives here (not in strings.xml) because it composes three
 * live values no single string template covers.
 */
@Composable
private fun updateSupportingText(appliedSeq: Long, pendingBytes: Long, lastCheck: Long): String? {
    if (pendingBytes > 0) {
        val mb = pendingBytes / 1_000_000
        return stringResource(R.string.settings_map_update_pending, mb)
    }
    if (appliedSeq > 0) {
        return pluralStringResource(R.plurals.settings_map_update_applied, appliedSeq.toInt(), appliedSeq)
    }
    if (lastCheck > 0) {
        return stringResource(R.string.settings_map_update_current)
    }
    return null
}
