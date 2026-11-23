package com.vayunmathur.appstore.ui
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.appstore.platform.loadBrowse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.platform.AppStoreViewModel

/**
 * Binds [AppStoreViewModel] to the stateless [BrowseScreen] for the section
 * [sectionId] — the `Route.Browse` argument, reloaded whenever it changes so
 * a restored entry shows its own section.
 */
@Composable
fun BrowsePage(
    viewModel: AppStoreViewModel,
    sectionId: String,
    onAppClick: (UnifiedApp) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(sectionId) { viewModel.loadBrowse(sectionId) }
    val state by viewModel.browse.collectAsState()
    BrowseScreen(
        state = state,
        onAppClick = onAppClick,
        onBack = onBack,
    )
}

