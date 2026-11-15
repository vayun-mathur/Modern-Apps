package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvSwitchRow
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * TV general settings: open-to default, DeArrow, keep-controls, notification bar, language.
 *
 * Same DataStore bindings as the phone `GeneralSettingsPage`; dropdowns become D-pad
 * option rows (one focusable row per choice) because a TV has no dropdown convention.
 * The list is constrained to a readable max width.
 */
@Composable
fun TvGeneralSettingsPage(
    ypvm: YouPipeViewModel,
) {
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val youtubeLanguage by ypvm.youtubeLanguage.collectAsState()
    val defaultPage by ypvm.defaultPage.collectAsState()
    val keepControlsVisible by ypvm.keepPlayerControlsVisible.collectAsState()
    val notificationStyleProgress by ypvm.notificationStyleProgressBar.collectAsState()
    val firstFocus = remember { FocusRequester() }

    // TV pins the transport overlay visible when the accessibility keep-visible is on —
    // same flag the phone player reads, no TV-specific path.
    LaunchedEffect(keepControlsVisible) {
        TvTransportState.pinned = keepControlsVisible
        if (keepControlsVisible) TvTransportState.reveal()
    }

    TvInitialFocus(firstFocus)
    TvSettingsBody {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TvScreenHeader(title = stringResource(R.string.settings_general_content))
            Column(
                Modifier.padding(start = 48.dp, end = 48.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvDefaultPageOptions(
                    defaultPage = defaultPage,
                    firstFocus = firstFocus,
                    onSelect = { ypvm.setDefaultPage(it) },
                )
                TvGeneralSwitches(
                    deArrowEnabled = deArrowEnabled, keepControlsVisible = keepControlsVisible,
                    notificationStyleProgress = notificationStyleProgress,
                    onDeArrowChange = { ypvm.setDeArrowEnabled(it) },
                    onKeepControlsChange = { ypvm.setKeepPlayerControlsVisible(it) },
                    onNotificationStyleChange = { ypvm.setNotificationStyleProgressBar(it) },
                )
                TvLanguageOptions(
                    youtubeLanguage = youtubeLanguage,
                    onSelect = { ypvm.setYouTubeLanguage(it) },
                )
            }
        }
    }
}

@Composable
private fun TvDefaultPageOptions(
    defaultPage: String,
    firstFocus: FocusRequester,
    onSelect: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.label_default_page),
        style = MaterialTheme.typography.titleMedium,
    )
    com.vayunmathur.youpipe.DEFAULT_PAGE_OPTIONS.forEachIndexed { index, (key, labelRes) ->
        TvListRow(
            headline = stringResource(labelRes),
            trailing = if (defaultPage == key) {
                { IconCheck() }
            } else {
                null
            },
            onClick = { onSelect(key) },
            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
        )
    }
}

@Composable
private fun TvGeneralSwitches(
    deArrowEnabled: Boolean,
    keepControlsVisible: Boolean,
    notificationStyleProgress: Boolean,
    onDeArrowChange: (Boolean) -> Unit,
    onKeepControlsChange: (Boolean) -> Unit,
    onNotificationStyleChange: (Boolean) -> Unit,
) {
    TvSwitchRow(
        headline = stringResource(R.string.label_dearrow),
        supporting = stringResource(R.string.label_dearrow_description),
        checked = deArrowEnabled,
        onCheckedChange = onDeArrowChange,
    )
    TvSwitchRow(
        headline = stringResource(R.string.label_keep_controls_visible),
        supporting = stringResource(R.string.label_keep_controls_visible_description),
        checked = keepControlsVisible,
        onCheckedChange = onKeepControlsChange,
    )
    TvSwitchRow(
        headline = stringResource(R.string.label_notification_style_progress),
        supporting = stringResource(R.string.label_notification_style_progress_description),
        checked = notificationStyleProgress,
        onCheckedChange = onNotificationStyleChange,
    )
}

@Composable
private fun TvLanguageOptions(youtubeLanguage: String, onSelect: (String) -> Unit) {
    Text(
        text = stringResource(R.string.label_youtube_language),
        style = MaterialTheme.typography.titleMedium,
    )
    TvListRow(
        headline = stringResource(R.string.label_system_default),
        trailing = if (youtubeLanguage.isBlank()) {
            { IconCheck() }
        } else {
            null
        },
        onClick = { onSelect("") },
    )
    YouPipeViewModel.Companion.YOUTUBE_LANGUAGES.forEach { (code, name) ->
        TvListRow(
            headline = name,
            trailing = if (youtubeLanguage == code) {
                { IconCheck() }
            } else {
                null
            },
            onClick = { onSelect(code) },
        )
    }
}
