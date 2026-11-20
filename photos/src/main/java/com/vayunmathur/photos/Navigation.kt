package com.vayunmathur.photos
import com.vayunmathur.photos.ui.TrashPage
import com.vayunmathur.photos.ui.PhotoPage
import com.vayunmathur.photos.ui.GalleryPage

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.PhotoMapViewModel
import com.vayunmathur.photos.platform.SecureFolderViewModel
import com.vayunmathur.photos.platform.viewerRouteFor
import com.vayunmathur.photos.ui.AlbumDetailPage
import com.vayunmathur.photos.ui.AlbumsPage
import com.vayunmathur.photos.ui.FavoritesPage
import com.vayunmathur.photos.ui.IndexViewUriEffect
import com.vayunmathur.photos.ui.MapPage
import com.vayunmathur.photos.ui.PeoplePage
import com.vayunmathur.photos.ui.RelockEffect
import com.vayunmathur.photos.ui.SecureFolderEntry
import com.vayunmathur.photos.ui.VaultViewerEntry
import com.vayunmathur.photos.ui.WallpaperPage

@Composable
fun Navigation(
    galleryViewModel: GalleryViewModel,
    photoMapViewModel: PhotoMapViewModel,
    secureFolderViewModel: SecureFolderViewModel,
    viewUri: Uri? = null,
) {
    // Opened via ACTION_VIEW from another app (e.g. the camera): that one photo is the whole app
    // for this launch, so it is the root of the stack and the gallery is not underneath it. Back
    // then belongs to the system, which finishes this Activity straight back to whoever fired the
    // intent - the same task they started us in - and runs the cross-activity predictive back
    // animation itself. An in-app back handler would swallow that gesture, animate nothing, and
    // land the user on a home screen they never asked for.
    //
    // The MediaStore _id in a content URI equals Photo.id, so the page can be named up front and
    // render the incoming URI directly while the background index writes the row and the full sync
    // populates the rest of the library for swiping. PhotoPage reconciles to the DB-backed pager.
    val viewerRoute = remember(viewUri) { viewerRouteFor(viewUri) }

    val backStack = rememberNavBackStack<Route>(viewerRoute ?: Route.Gallery)
    val vaultPhotoDao by secureFolderViewModel.vaultPhotoDao.collectAsStateWithLifecycle()
    val vaultPassword by secureFolderViewModel.vaultPassword.collectAsStateWithLifecycle()
    val relockOnExit by secureFolderViewModel.relockOnExit.collectAsStateWithLifecycle()

    RelockEffect(backStack, relockOnExit, vaultPhotoDao, secureFolderViewModel)
    IndexViewUriEffect(viewUri, galleryViewModel)

    // PhotoPage pops itself when its last remaining photo is deleted. With the viewer as the root
    // there is nothing under it to pop to, and leaving the viewer here means leaving the app: hand
    // back to whoever sent us rather than hand NavDisplay an empty stack, which it rejects.
    val activity = LocalActivity.current
    if (backStack.backStack.isEmpty()) {
        LaunchedEffect(Unit) { activity?.finish() }
        return
    }

    MainNavigation(backStack) {
        // List role for the gallery so medium widths pair it with the viewer; Sibling motion
        // kept so switching bottom-nav tabs still crossfades instead of sliding.
        entry<Route.Gallery>(metadata = ListPage() + SiblingPage()) {
            GalleryPage(backStack, galleryViewModel, secureFolderViewModel)
        }

        entry<Route.Map>(metadata = SiblingPage()) {
            MapPage(backStack, galleryViewModel, photoMapViewModel)
        }

        entry<Route.People>(metadata = SiblingPage()) {
            PeoplePage(backStack, galleryViewModel)
        }

        entry<Route.Albums>(metadata = SiblingPage()) {
            AlbumsPage(backStack, galleryViewModel, secureFolderViewModel)
        }

        entry<Route.AlbumDetail>(metadata = ListDetailPage()) {
            AlbumDetailPage(backStack, galleryViewModel, it.albumName)
        }

        entry<Route.PhotoPage>(metadata = ListDetailPage() + MorphPage()) {
            PhotoPage(galleryViewModel, photoMapViewModel, it.id, it.sourceKey, it.pendingUri, backStack)
        }

        entry<Route.Wallpaper>(metadata = ListDetailPage()) {
            WallpaperPage(backStack, it.uri)
        }

        // Built-in collections, opened from the Albums grid's default tiles
        // (Favorites, Trash, Secure Folder). Detail pages: they push over Albums
        // and show a back arrow instead of the bottom bar.
        entry<Route.Favorites> {
            FavoritesPage(backStack, galleryViewModel, secureFolderViewModel)
        }

        entry<Route.Trash> {
            TrashPage(backStack, galleryViewModel)
        }

        entry<Route.SecureFolder> {
            SecureFolderEntry(backStack, secureFolderViewModel, vaultPhotoDao != null, vaultPassword)
        }

        entry<Route.VaultViewer>(metadata = ListDetailPage()) {
            VaultViewerEntry(backStack, it.vaultId, vaultPassword, secureFolderViewModel)
        }
    }
}
