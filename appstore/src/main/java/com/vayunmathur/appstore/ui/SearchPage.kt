package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.appstore.platform.SourceFilter

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.CircularProgressIndicator

/** Binds [AppStoreViewModel] to the stateless [SearchScreen]. */
@Composable
fun SearchPage(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    isActive: Boolean = true,
) {
    val state by viewModel.search.collectAsState()
    // Opening the search tab is a request to type. Only on a fresh one, though — coming
    // back from an app's page should not throw the keyboard over the results. And only when
    // this page is the one the user is actually on: a pager composes its neighbours, so
    // focusing while merely adjacent would drag the pager onto search mid-swipe.
    SearchScreen(
        state = state,
        actions = viewModel,
        onAppClick = onAppClick,
        autoFocus = isActive && state.query.isBlank(),
    )
}


@Composable
internal fun Loading() {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

internal fun SourceFilter.labelRes(): Int = when (this) {
    SourceFilter.ALL -> R.string.filter_all
    SourceFilter.MODERN_APPS -> R.string.source_chip_modern_apps
    SourceFilter.PROPRIETARY -> R.string.source_chip_proprietary
    SourceFilter.FDROID -> R.string.source_chip_fdroid
    SourceFilter.IZZY -> R.string.source_chip_izzy
    SourceFilter.PLAYSTORE -> R.string.source_chip_play
    SourceFilter.ACCRESCENT -> R.string.source_chip_accrescent
}
