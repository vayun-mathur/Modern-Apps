package com.vayunmathur.appstore.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.platform.AppStoreViewModel

/** Binds [AppStoreViewModel] to the stateless [LibraryScreen]. */
@Composable
fun LibraryPage(viewModel: AppStoreViewModel, onAppClick: (UnifiedApp) -> Unit) {
    val state by viewModel.library.collectAsState()
    LibraryScreen(state = state, actions = viewModel, onAppClick = onAppClick)
}

