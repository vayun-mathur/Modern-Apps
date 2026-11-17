package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvSwitchRow
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.YouPipeViewModel.Companion.ALL_SPONSOR_CATEGORIES

/**
 * TV SponsorBlock settings: one switch row per category.
 *
 * Same `ALL_SPONSOR_CATEGORIES` + `toggleSponsorBlockCategory` as the phone page.
 */
@Composable
fun TvSponsorBlockSettingsPage(
    ypvm: YouPipeViewModel,
) {
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()
    val firstFocus = remember { FocusRequester() }

    TvInitialFocus(firstFocus)
    TvSettingsBody {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TvScreenHeader(title = stringResource(R.string.settings_sponsorblock))
            Column(
                Modifier.padding(start = 48.dp, end = 48.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ALL_SPONSOR_CATEGORIES.forEachIndexed { index, category ->
                    TvSwitchRow(
                        headline = YouPipeViewModel.Companion.SPONSOR_CATEGORY_LABELS[category]
                            ?: category,
                        checked = category in sponsorBlockCategories,
                        onCheckedChange = { ypvm.toggleSponsorBlockCategory(category) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}
