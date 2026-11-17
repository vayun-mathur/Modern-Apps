package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.presetLabel
import com.vayunmathur.youpipe.domain.RecommendationPreset
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.YouPipeViewModel.Companion.ALL_SPONSOR_CATEGORIES

/**
 * TV settings hub: the four sections as [TvListRow]s with summaries.
 *
 * Mirrors the phone `SettingsPage` (sponsor summary + recommendation preset summary) with
 * no app bars — the rail is the chrome. The list is constrained to a readable max width.
 */
@Composable
fun TvSettingsPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()
    val recPrefs by ypvm.recommendationPreferences.collectAsState()
    val blockedCount = sponsorBlockCategories.count { it in ALL_SPONSOR_CATEGORIES }
    val currentPreset = RecommendationPreset.entries.firstOrNull { it.name == recPrefs.preset }
    val presetSummary = currentPreset?.let { presetLabel(it) } ?: stringResource(R.string.preset_custom)
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)
    TvSettingsBody {
        TvSettingsList(backStack, blockedCount, presetSummary, firstFocus)
    }
}

@Composable
private fun TvSettingsList(
    backStack: NavBackStack<TvRoute>,
    blockedCount: Int,
    presetSummary: String,
    firstFocus: FocusRequester,
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = tvContentPadding(top = 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tvSettingsHeader()
        tvSettingsGeneralRow(firstFocus, backStack)
        tvSettingsSponsorRow(blockedCount, backStack)
        tvSettingsRecommendationsRow(presetSummary, backStack)
        tvSettingsDataRow(backStack)
    }
}

private fun LazyListScope.tvSettingsHeader() {
    item(key = "header") {
        androidx.tv.material3.Text(
            text = stringResource(UiR.string.settings),
            style = androidx.tv.material3.MaterialTheme.typography.headlineLarge,
            color = androidx.tv.material3.MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 28.dp, bottom = 16.dp),
        )
    }
}

private fun LazyListScope.tvSettingsGeneralRow(
    firstFocus: FocusRequester,
    backStack: NavBackStack<TvRoute>,
) {
    item(key = "general") {
        TvListRow(
            headline = stringResource(R.string.settings_general_content),
            trailing = { IconChevronRight() },
            onClick = { backStack.add(TvRoute.SettingsGeneral) },
            modifier = Modifier.focusRequester(firstFocus),
        )
    }
}

private fun LazyListScope.tvSettingsSponsorRow(
    blockedCount: Int,
    backStack: NavBackStack<TvRoute>,
) {
    item(key = "sponsorblock") {
        TvListRow(
            headline = stringResource(R.string.settings_sponsorblock),
            supporting = pluralStringResource(
                R.plurals.settings_sponsorblock_summary,
                blockedCount,
                blockedCount,
                ALL_SPONSOR_CATEGORIES.size,
            ),
            trailing = { IconChevronRight() },
            onClick = { backStack.add(TvRoute.SettingsSponsorBlock) },
        )
    }
}

private fun LazyListScope.tvSettingsRecommendationsRow(
    presetSummary: String,
    backStack: NavBackStack<TvRoute>,
) {
    item(key = "recommendations") {
        TvListRow(
            headline = stringResource(R.string.label_recommendations),
            supporting = presetSummary,
            trailing = { IconChevronRight() },
            onClick = { backStack.add(TvRoute.SettingsRecommendations) },
        )
    }
}

private fun LazyListScope.tvSettingsDataRow(backStack: NavBackStack<TvRoute>) {
    item(key = "data") {
        TvListRow(
            headline = stringResource(R.string.settings_data),
            trailing = { IconChevronRight() },
            onClick = { backStack.add(TvRoute.SettingsData) },
        )
    }
}
