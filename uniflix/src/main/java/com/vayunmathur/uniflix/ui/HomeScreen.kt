package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconWidgets
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.platform.HomeUiState

/** Home: trending/top-rated rows plus hub shortcuts. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenContinue: () -> Unit,
    onOpenAddons: () -> Unit,
) {
    AppScaffold(
        isRefreshing = state.loading,
        title = "Uniflix",
        actions = {
            IconButton(onClick = onOpenSearch) { IconSearch() }
            IconButton(onClick = onOpenAddons) { IconWidgets() }
        },
    ) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenMovies) { Text("Movies") }
                    Button(onClick = onOpenSeries) { Text("Series") }
                    IconButton(onClick = onOpenLists) { IconList() }
                    IconButton(onClick = onOpenContinue) { IconHistory() }
                }
                MediaRow("Trending movies", state.trendingMovies, onOpenDetail)
                MediaRow("Trending series", state.trendingTv, onOpenDetail)
                MediaRow("Top-rated movies", state.topMovies, onOpenDetail)
                MediaRow("Top-rated series", state.topTv, onOpenDetail)
            }
        }
    }
}
