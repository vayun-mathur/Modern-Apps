package com.vayunmathur.photos.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFavoriteBorder
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.photos.R
import com.vayunmathur.photos.util.GalleryActions

/**
 * The multi-select top bar actions shared by the gallery and the Favorites
 * album: favorite, add to album, move to the Secure Folder, trash.
 *
 * The heart shows what tapping it does: filled ("remove from favorites") only
 * when every selected item already is a favorite, outlined ("favorite")
 * otherwise, matching [com.vayunmathur.photos.domain.FavoriteRule].
 */
@Composable
fun GallerySelectionActions(
    allFavorite: Boolean,
    actions: GalleryActions,
    onAddToAlbum: () -> Unit,
) {
    IconButton(onClick = { actions.toggleFavoriteSelection() }) {
        if (allFavorite) {
            IconFavorite(contentDescription = stringResource(R.string.action_unfavorite))
        } else {
            IconFavoriteBorder(contentDescription = stringResource(R.string.action_favorite))
        }
    }
    IconButton(onClick = onAddToAlbum) {
        IconAlbum()
    }
    IconButton(onClick = { actions.moveSelectionToSecureFolder() }) {
        IconLock()
    }
    IconButton(onClick = { actions.trashSelection() }) {
        IconDelete()
    }
}
