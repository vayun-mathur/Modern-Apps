package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.domain.MediaItem

/** Poster grid for the Movies and Series hubs. */
@Composable
fun MediaGridScreen(
    title: String,
    loading: Boolean,
    items: List<MediaItem>,
    onBack: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
) {
    AppScaffold(
        title = title,
        onNavigateBack = onBack,
    ) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                return@DesktopMaxWidthContainer
            }
            if (items.isEmpty()) {
                Text("Nothing here yet.", modifier = Modifier.padding(16.dp))
                return@DesktopMaxWidthContainer
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { "${it.mediaType}:${it.tmdbId}" }) { item ->
                    MediaCard(
                        item = item,
                        onOpen = { onOpenDetail(item) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
