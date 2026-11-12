package com.vayunmathur.photos.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.log.Log
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryActions
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.SecureFolderViewModel

/**
 * The [GalleryActions] behind a selectable photo grid: the ViewModel's own
 * callbacks plus the MediaStore operations that need activity-result
 * launchers (trash, Secure Folder, add to album, favorite). Shared by the
 * gallery and the Favorites album so both grids select and act alike.
 *
 * [photos] and [selectedIds] are the grid's own items and selection; every
 * action applies to the selected items among [photos] only.
 */
@Composable
fun rememberGalleryActions(
    galleryViewModel: GalleryViewModel,
    secureFolderViewModel: SecureFolderViewModel,
    photos: List<Photo>,
    selectedIds: Set<Long>,
): GalleryActions {
    val context = LocalContext.current

    val mediaResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // A pending album move consumes the grant and does its own refresh; the
            // trash/delete/secure-folder paths that share this launcher fall through
            // to the plain clear-and-resync.
            if (!galleryViewModel.consumePendingMove()) {
                galleryViewModel.clearSelection()
                galleryViewModel.runSync()
            }
        }
    }

    // A favorite request that went through clears the selection, like trash.
    val toggleFavorite = rememberFavoriteToggle(
        galleryViewModel.favoriteToggler,
        onApplied = { galleryViewModel.clearSelection() },
    )

    // Read through State holders so the lambdas below see current values without
    // being reallocated each recomposition — which is what lets the `actions`
    // object be remembered rather than rebuilt (and every grid item lambda
    // invalidated) on every frame.
    val currentPhotos by rememberUpdatedState(photos)
    val currentSelectedIds by rememberUpdatedState(selectedIds)

    return remember(galleryViewModel, secureFolderViewModel, context, toggleFavorite) {
        // The ViewModel is the actions implementation; the two MediaStore operations are
        // the exception, because they need the launchers created above.
        object : GalleryActions by galleryViewModel {
            override fun moveSelectionToSecureFolder() {
                val activity = context as FragmentActivity
                val selectedPhotos = currentPhotos.filter { it.id in currentSelectedIds }

                secureFolderViewModel.unlock(
                    activity,
                    onSuccess = { _, _ ->
                        secureFolderViewModel.moveToSecure(
                            photos = selectedPhotos,
                            sourceRepository = com.vayunmathur.photos.data.PhotosRepository.get(context.applicationContext),
                        ) { urisToDelete ->
                            // Use MediaStore operations to delete files
                            // MANAGE_MEDIA permission is required to run the app (checked in PermissionsWrapper)
                            try {
                                val pendingIntent = MediaStore.createDeleteRequest(
                                    context.contentResolver,
                                    urisToDelete
                                )
                                // With MANAGE_MEDIA permission granted, this will delete without popup
                                mediaResultLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                )
                            } catch (e: SecurityException) {
                                Log.error("GalleryPage", "MediaStore delete request failed", e)
                                // Fallback: clear selection and refresh anyway
                                galleryViewModel.clearSelection()
                                galleryViewModel.runSync()
                            } catch (e: ActivityNotFoundException) {
                                Log.error("GalleryPage", "MediaStore delete request failed", e)
                                // Fallback: clear selection and refresh anyway
                                galleryViewModel.clearSelection()
                                galleryViewModel.runSync()
                            }
                        }
                    },
                    onFailure = {},
                )
            }

            override fun trashSelection() {
                val uris = currentPhotos.filter { it.id in currentSelectedIds }.map { it.uri.toUri() }
                val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
                mediaResultLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }

            override fun toggleFavoriteSelection() {
                toggleFavorite(currentPhotos.filter { it.id in currentSelectedIds })
            }

            override fun addSelectionToAlbum(name: String) {
                val album = com.vayunmathur.photos.util.AlbumMediaStore.sanitizeAlbumName(name)
                if (album.isBlank()) return
                val selected = currentPhotos.filter { it.id in currentSelectedIds }
                if (selected.isEmpty()) return
                galleryViewModel.requestAlbumMove(selected, album)
                val pendingIntent = MediaStore.createWriteRequest(
                    context.contentResolver,
                    com.vayunmathur.photos.util.AlbumMediaStore.urisOf(selected),
                )
                mediaResultLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }
        }
    }
}
