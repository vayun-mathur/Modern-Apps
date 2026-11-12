package com.vayunmathur.photos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.GalleryUiState
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.SecureFolderViewModel

/** Binds [GalleryViewModel] to the stateless [FavoritesScreen]. */
@Composable
fun FavoritesPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val favorites by galleryViewModel.favorites.collectAsState()
    val albums by galleryViewModel.albums.collectAsState()
    val allSelected by galleryViewModel.selectedIds.collectAsState()
    val isRefreshing by galleryViewModel.isRefreshing.collectAsState()

    // Only selection among the favorites counts here, so a selection left over
    // from the gallery grid doesn't leak into this page's action bar.
    val selectedIds by remember {
        derivedStateOf { allSelected.intersect(favorites.mapTo(HashSet()) { it.id }) }
    }

    FavoritesScreen(
        backStack = backStack,
        state = GalleryUiState(
            photos = favorites,
            selectedIds = selectedIds,
            isRefreshing = isRefreshing,
            albums = albums,
        ),
        actions = rememberGalleryActions(galleryViewModel, secureFolderViewModel, favorites, selectedIds),
    )
}
