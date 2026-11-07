package com.vayunmathur.photos.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.GalleryActions
import com.vayunmathur.photos.util.GalleryUiState
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.SecureFolderViewModel
import com.vayunmathur.photos.util.enqueueSyncWork

/** Binds [GalleryViewModel] to the stateless [GalleryScreen]. */
@Composable
fun GalleryPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val allPhotos by galleryViewModel.photos.collectAsState()
    val photos by remember { derivedStateOf { allPhotos.filter { !it.isTrashed } } }
    val albums by galleryViewModel.albums.collectAsState()
    val context = LocalContext.current

    val selectedIds by galleryViewModel.selectedIds.collectAsState()
    val isRefreshing by galleryViewModel.isRefreshing.collectAsState()

    val searchQuery by galleryViewModel.searchQuery.collectAsState()
    val searchResults by galleryViewModel.searchResults.collectAsState()
    val searchAiState by galleryViewModel.searchAiState.collectAsState()
    val ocrCount by galleryViewModel.ocrCount.collectAsState()
    val clipCount by galleryViewModel.clipCount.collectAsState()
    val indexTargetCount by galleryViewModel.indexTargetCount.collectAsState()

    LaunchedEffect(Unit) {
        galleryViewModel.runSync()
        // Same no-WorkManager story as TrashPage (see runSyncOnce): the
        // ViewModel's own runSync is seam-covered per instance, but enqueueSync
        // is not, so route it through the shared hook here.
        enqueueSyncWork(context)
    }

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

    // Helper to request MANAGE_MEDIA permission
    val manageMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // User returned from Settings - permission may or may not be granted
        // The next delete attempt will check again
    }

    fun requestManageMediaPermission() {
        if (!MediaStore.canManageMedia(context)) {
            val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                data = "package:${context.packageName}".toUri()
            }
            manageMediaLauncher.launch(intent)
        }
    }

    // Read through State holders so the lambdas below see current values without
    // being reallocated each recomposition — which is what lets the `actions`
    // object be remembered rather than rebuilt (and every grid item lambda
    // invalidated) on every frame.
    val currentPhotos by rememberUpdatedState(photos)
    val currentSelectedIds by rememberUpdatedState(selectedIds)

    val actions = remember(galleryViewModel, secureFolderViewModel, context) {
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

    GalleryScreen(
        backStack = backStack,
        state = GalleryUiState(
            photos = photos,
            selectedIds = selectedIds,
            isRefreshing = isRefreshing,
            searchQuery = searchQuery,
            searchResults = searchResults,
            searchAiState = searchAiState,
            ocrCount = ocrCount,
            ocrTargetCount = indexTargetCount,
            clipCount = clipCount,
            clipTargetCount = indexTargetCount,
            albums = albums,
        ),
        actions = actions,
    )
}
