package com.vayunmathur.photos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.platform.GalleryUiState
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.SecureFolderViewModel
import com.vayunmathur.photos.platform.enqueueSyncWork

/** Binds [GalleryViewModel] to the stateless [GalleryScreen]. */
@Composable
fun GalleryPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    secureFolderViewModel: SecureFolderViewModel,
) {
    // Untrashed rows projected off the main thread in the ViewModel
    // (galleryPhotos); filtering here used to run O(library) in composition.
    val photos by galleryViewModel.galleryPhotos.collectAsState()
    val isLoading by galleryViewModel.galleryLoading.collectAsState()
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

    // Foreground freshness: while this screen is started, a MediaStore
    // content observer imports just-taken photos within moments (debounced
    // item-URI sync in the ViewModel); on stop it unregisters, so no
    // observer leaks into the background. The WorkManager content trigger
    // above remains the background safety net.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, galleryViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> galleryViewModel.startForegroundObserving()
                Lifecycle.Event.ON_STOP -> galleryViewModel.stopForegroundObserving()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            galleryViewModel.stopForegroundObserving()
        }
    }

    // Trash/delete/album actions request MANAGE_MEDIA contextually inside
    // rememberGalleryActions (rememberManageMediaAction); nothing to ask for
    // on this browsing screen.
    val actions = rememberGalleryActions(galleryViewModel, secureFolderViewModel, photos, selectedIds)

    GalleryScreen(
        backStack = backStack,
        state = GalleryUiState(
            photos = photos,
            isLoading = isLoading,
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
