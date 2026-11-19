package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.platform.DEFAULT_PAGE_OPTIONS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.YouPipeViewModel.Companion.YOUTUBE_LANGUAGES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
) {
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val youtubeLanguage by ypvm.youtubeLanguage.collectAsState()
    val defaultPage by ypvm.defaultPage.collectAsState()
    val keepControlsVisible by ypvm.keepPlayerControlsVisible.collectAsState()
    val notificationStyleProgress by ypvm.notificationStyleProgressBar.collectAsState()

    AppScaffold(
        title = stringResource(R.string.settings_general_content),
        backStack = backStack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = stringResource(R.string.label_general)) {
                DefaultPageSelector(
                    selectedKey = defaultPage,
                    onSelect = { ypvm.setDefaultPage(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.label_dearrow),
                    supportingText = stringResource(R.string.label_dearrow_description),
                    checked = deArrowEnabled,
                    onCheckedChange = { ypvm.setDeArrowEnabled(it) },
                )
            }
            SettingsSection(title = stringResource(R.string.label_player)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.label_keep_controls_visible),
                    supportingText = stringResource(R.string.label_keep_controls_visible_description),
                    checked = keepControlsVisible,
                    onCheckedChange = { ypvm.setKeepPlayerControlsVisible(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.label_notification_style_progress),
                    supportingText = stringResource(R.string.label_notification_style_progress_description),
                    checked = notificationStyleProgress,
                    onCheckedChange = { ypvm.setNotificationStyleProgressBar(it) },
                )
            }
            SettingsSection(title = stringResource(R.string.label_youtube_content)) {
                LanguageSelector(
                    selectedCode = youtubeLanguage,
                    onSelect = { ypvm.setYouTubeLanguage(it) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(selectedCode: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val systemDefault = stringResource(R.string.label_system_default)
    val options = remember { listOf("" to null) + YOUTUBE_LANGUAGES.map { it.first to it.second } }
    val currentName = YOUTUBE_LANGUAGES.firstOrNull { it.first == selectedCode }?.second ?: systemDefault

    Box {
        ListItem(
            content = { Text(stringResource(R.string.label_youtube_language)) },
            supportingContent = { Text(currentName) },
            trailingContent = { IconArrowDropDown() },
            modifier = Modifier.clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name ?: systemDefault) },
                    onClick = { onSelect(code); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultPageSelector(selectedKey: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabelRes = (DEFAULT_PAGE_OPTIONS.firstOrNull { it.first == selectedKey }
        ?: DEFAULT_PAGE_OPTIONS.first()).second

    Box {
        ListItem(
            content = { Text(stringResource(R.string.label_default_page)) },
            supportingContent = { Text(stringResource(currentLabelRes)) },
            trailingContent = { IconArrowDropDown() },
            modifier = Modifier.clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DEFAULT_PAGE_OPTIONS.forEach { (key, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = { onSelect(key); expanded = false },
                )
            }
        }
    }
}
