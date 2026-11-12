package com.vayunmathur.photos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.AlbumsUiState
import com.vayunmathur.photos.util.DefaultAlbum
import com.vayunmathur.photos.util.DefaultAlbumKind
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.SecureFolderViewModel

/** Binds [GalleryViewModel] to the stateless [AlbumsScreen]. */
@Composable
fun AlbumsPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val albums by galleryViewModel.albums.collectAsState()
    val allPhotos by galleryViewModel.photos.collectAsState()
    val trashCount by remember { derivedStateOf { allPhotos.count { it.isTrashed } } }
    val favorites by galleryViewModel.favorites.collectAsState()
    val vaultPhotos by secureFolderViewModel.photos.collectAsState()
    val vaultDao by secureFolderViewModel.vaultPhotoDao.collectAsState()

    AlbumsScreen(
        backStack = backStack,
        state = AlbumsUiState(
            albums = albums,
            defaultAlbums = listOf(
                // Covered by the newest favorite (the list is newest first).
                DefaultAlbum(DefaultAlbumKind.FAVORITES, favorites.size, cover = favorites.firstOrNull()),
                DefaultAlbum(DefaultAlbumKind.TRASH, trashCount),
                // The vault size stays private while locked: hide the count line.
                DefaultAlbum(DefaultAlbumKind.SECURE_FOLDER, vaultPhotos.size.takeIf { vaultDao != null }),
            ),
        ),
    )
}
