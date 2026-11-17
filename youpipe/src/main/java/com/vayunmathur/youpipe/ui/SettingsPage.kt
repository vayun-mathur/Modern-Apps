package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.domain.RecommendationPreset
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.YouPipeViewModel.Companion.ALL_SPONSOR_CATEGORIES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
) {
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()
    val recPrefs by ypvm.recommendationPreferences.collectAsState()

    val blockedCount = sponsorBlockCategories.count { it in ALL_SPONSOR_CATEGORIES }
    val currentPreset = RecommendationPreset.entries.firstOrNull { it.name == recPrefs.preset }
    val presetSummary = currentPreset?.let { presetLabel(it) } ?: stringResource(R.string.preset_custom)

    AppScaffold(
        title = stringResource(UiR.string.settings),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_general_content),
                onClick = { backStack.add(Route.SettingsGeneral) },
            )
            SettingsRow(
                title = stringResource(R.string.settings_sponsorblock),
                supportingText = pluralStringResource(
                    R.plurals.settings_sponsorblock_summary,
                    blockedCount,
                    blockedCount,
                    ALL_SPONSOR_CATEGORIES.size,
                ),
                onClick = { backStack.add(Route.SettingsSponsorBlock) },
            )
            SettingsRow(
                title = stringResource(R.string.label_recommendations),
                supportingText = presetSummary,
                onClick = { backStack.add(Route.SettingsRecommendations) },
            )
            SettingsRow(
                title = stringResource(R.string.settings_data),
                onClick = { backStack.add(Route.SettingsData) },
            )
        }
    }
}
