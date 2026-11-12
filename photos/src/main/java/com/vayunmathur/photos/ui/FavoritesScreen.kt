package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.domain.FavoriteRule
import com.vayunmathur.photos.pushPhoto
import com.vayunmathur.photos.util.GalleryActions
import com.vayunmathur.photos.util.GalleryUiState
import com.vayunmathur.photos.util.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The Favorites album (#912): every MediaStore favorite, newest first, with no
 * dependency on the ViewModel so it can be rendered from a `@Preview`.
 *
 * The grid matches the gallery's: month grouping, pinch-to-zoom columns
 * (shared through [LocalColumnCount]), pull-to-refresh, the same multi-select
 * actions ([GallerySelectionActions]) and the same viewer, opened over this
 * list. [state] carries the favorites as its photos; search fields are unused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    backStack: NavBackStack<Route>,
    state: GalleryUiState,
    actions: GalleryActions,
    /**
     * How a grid tile paints its photo. Previews pass a placeholder, because
     * Layoutlib has neither MediaStore nor the [ImageLoader] singleton
     * MainActivity initialises.
     */
    thumbnail: @Composable (Photo, Modifier) -> Unit = { photo, modifier -> ImageLoader.PhotoItem(photo, modifier) },
) {
    val photos = state.photos
    val isSelectionMode = state.selectedIds.isNotEmpty()
    val allFavorite = remember(photos, state.selectedIds) {
        !FavoriteRule.targetValue(photos.filter { it.id in state.selectedIds }.map { it.isFavorite })
    }
    var showAlbumPicker by remember { mutableStateOf(false) }
    var columnCount by LocalColumnCount.current
    val resources = LocalResources.current

    // Same off-main-thread grouping as the album and gallery grids; the seed
    // keeps the first frame (and a preview) populated.
    val initialGrouping = remember { groupPhotosByMonth(photos, resources) }
    val photosGroupedByMonth by produceState(initialValue = initialGrouping, photos, resources) {
        value = withContext(Dispatchers.Default) { groupPhotosByMonth(photos, resources) }
    }

    AppScaffold(
        title = if (isSelectionMode) {
            stringResource(R.string.items_selected, state.selectedIds.size)
        } else {
            stringResource(R.string.label_favorites)
        },
        onNavigateBack = if (isSelectionMode) null else ({ backStack.pop() }),
        onClose = if (isSelectionMode) ({ actions.clearSelection() }) else null,
        scrollBehavior = appBarScrollBehavior(),
        actions = {
            if (isSelectionMode) {
                GallerySelectionActions(allFavorite, actions, onAddToAlbum = { showAlbumPicker = true })
            }
        },
    ) { padding ->
        if (photos.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.favorites_empty),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            AppPullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { actions.runSync() },
                modifier = Modifier.fillMaxSize(),
                topClearance = padding.calculateTopPadding(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .pinchToZoomColumns({ columnCount }, { columnCount = it })
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columnCount.roundToInt().coerceIn(2, 8)),
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        photosGroupedByMonth.forEach { (month, photosInMonth) ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    month,
                                    Modifier.padding(top = 16.dp, bottom = 8.dp, start = 16.dp),
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            items(photosInMonth, key = { it.id }, contentType = { "photo_thumbnail" }) { photo ->
                                ImageLoader.SelectablePhotoItem(
                                    photo = photo,
                                    isSelected = photo.id in state.selectedIds,
                                    isSelectionMode = isSelectionMode,
                                    onToggleSelection = { actions.toggleSelection(photo.id) },
                                    onClick = {
                                        if (isSelectionMode) {
                                            actions.toggleSelection(photo.id)
                                        } else {
                                            backStack.pushPhoto(Route.PhotoPage(photo.id, photos))
                                        }
                                    },
                                    thumbnail = thumbnail,
                                    sharedKey = photo.id,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAlbumPicker) {
        AlbumPickerDialog(
            existingAlbums = state.albums.map { it.name },
            onDismiss = { showAlbumPicker = false },
            onConfirm = { name ->
                showAlbumPicker = false
                actions.addSelectionToAlbum(name)
            },
        )
    }
}
