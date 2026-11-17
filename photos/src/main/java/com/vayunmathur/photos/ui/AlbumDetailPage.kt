package com.vayunmathur.photos.ui

import android.app.Activity
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.platform.AlbumMediaStore
import com.vayunmathur.photos.platform.GalleryViewModel

/** Binds [GalleryViewModel] to the stateless [AlbumDetailScreen] for one album. */
@Composable
fun AlbumDetailPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    albumName: String,
) {
    val context = LocalContext.current
    val albums by galleryViewModel.albums.collectAsState()
    val album by remember { derivedStateOf { albums.firstOrNull { it.name == albumName } } }
    val allSelected by galleryViewModel.selectedIds.collectAsState()
    val isRefreshing by galleryViewModel.isRefreshing.collectAsState()

    // Only selection within this album's photos counts here, so a selection left
    // over from the gallery grid doesn't leak into the album's action bar.
    val selectedInAlbum by remember {
        derivedStateOf {
            val ids = album?.photos.orEmpty().map { it.id }.toSet()
            allSelected.intersect(ids)
        }
    }

    // Set after requesting a delete so the screen pops once the move is granted;
    // the album ceases to exist and there is nothing left to show.
    var pendingPop by remember { mutableStateOf(false) }

    val mediaResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            galleryViewModel.consumePendingMove()
            if (pendingPop) {
                pendingPop = false
                backStack.pop()
            }
        } else {
            pendingPop = false
        }
    }

    fun launchMove(photos: List<Photo>, target: String?) {
        if (photos.isEmpty()) return
        galleryViewModel.requestAlbumMove(photos, target)
        val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, AlbumMediaStore.urisOf(photos))
        mediaResultLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    AlbumDetailScreen(
        backStack = backStack,
        albumName = albumName,
        album = album,
        selectedIds = selectedInAlbum,
        onToggleSelection = { galleryViewModel.toggleSelection(it) },
        onClearSelection = { galleryViewModel.clearSelection() },
        onRemoveFromAlbum = {
            val selected = album?.photos.orEmpty().filter { it.id in selectedInAlbum }
            launchMove(selected, null)
        },
        onSetCover = { photoId ->
            galleryViewModel.setAlbumCover(albumName, photoId)
            galleryViewModel.clearSelection()
        },
        onRenameAlbum = { newName ->
            val sanitized = AlbumMediaStore.sanitizeAlbumName(newName)
            if (sanitized.isNotBlank() && sanitized != albumName) {
                launchMove(album?.photos.orEmpty(), sanitized)
            }
        },
        onDeleteAlbum = {
            pendingPop = true
            launchMove(album?.photos.orEmpty(), null)
        },
        isRefreshing = isRefreshing,
        onRefresh = { galleryViewModel.runSync() },
    )
}
