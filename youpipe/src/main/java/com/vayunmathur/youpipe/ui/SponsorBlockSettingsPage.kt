package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.YouPipeViewModel.Companion.ALL_SPONSOR_CATEGORIES
import com.vayunmathur.youpipe.platform.YouPipeViewModel.Companion.SPONSOR_CATEGORY_LABELS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SponsorBlockSettingsPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
) {
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()

    AppScaffold(
        title = stringResource(R.string.settings_sponsorblock),
        backStack = backStack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = stringResource(R.string.label_sponsorblock)) {
                ALL_SPONSOR_CATEGORIES.forEach { category ->
                    SettingsSwitchRow(
                        title = SPONSOR_CATEGORY_LABELS[category] ?: category,
                        checked = category in sponsorBlockCategories,
                        onCheckedChange = { ypvm.toggleSponsorBlockCategory(category) },
                    )
                }
            }
        }
    }
}
