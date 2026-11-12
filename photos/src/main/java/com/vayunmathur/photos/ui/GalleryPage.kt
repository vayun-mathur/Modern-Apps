package com.vayunmathur.photos.ui

import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
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

    val actions = rememberGalleryActions(galleryViewModel, secureFolderViewModel, photos, selectedIds)

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
