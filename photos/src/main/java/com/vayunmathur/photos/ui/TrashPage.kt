package com.vayunmathur.photos.ui
import com.vayunmathur.photos.R

import android.app.Activity
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconUnarchive
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.enqueueSyncWork
import com.vayunmathur.photos.util.runSyncOnce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashPage(backStack: NavBackStack<Route>, galleryViewModel: GalleryViewModel) {
    val allPhotos by galleryViewModel.photos.collectAsState()
    val trashedPhotos by remember { derivedStateOf { allPhotos.filter { it.isTrashed } } }
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        runSyncOnce(context)
        enqueueSyncWork(context)
    }

    var columnCount by LocalColumnCount.current

    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedIds.clear()
            runSyncOnce(context)
        }
    }

    // Grouping is O(N) with a timezone conversion per photo, so it runs on
    // Dispatchers.Default. The seed is computed once so the grid isn't blank on the
    // first frame; produceState's initialValue is eagerly evaluated, hence the
    // remember rather than an inline call.
    val initialGrouping = remember { groupPhotosByMonth(trashedPhotos, resources) }
    val photosGroupedByMonth by produceState(
        initialValue = initialGrouping,
        trashedPhotos,
        resources,
    ) {
        value = withContext(Dispatchers.Default) { groupPhotosByMonth(trashedPhotos, resources) }
    }

    val closeSelection: (() -> Unit)? = if (isSelectionMode) ({ selectedIds.clear() }) else null
    AppScaffold(
        title = if (isSelectionMode) {
            stringResource(com.vayunmathur.photos.R.string.items_selected, selectedIds.size)
        } else {
            stringResource(com.vayunmathur.photos.R.string.label_trash)
        },
        onNavigateBack = if (isSelectionMode) null else ({ backStack.pop() }),
        onClose = closeSelection,
        actions = {
            if (isSelectionMode) {
                IconButton(onClick = {
                    val uris = trashedPhotos.filter { it.id in selectedIds }.map { it.uri.toUri() }
                    val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, false)
                    trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                }) {
                    IconUnarchive() // Restore icon
                }
                IconButton(onClick = {
                    val uris = trashedPhotos.filter { it.id in selectedIds }.map { it.uri.toUri() }
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                }) {
                    IconDelete() // Permanent delete icon
                }
            } else {
                if (trashedPhotos.isNotEmpty()) {
                    IconButton(onClick = {
                        val uris = trashedPhotos.map { it.uri.toUri() }
                        val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                        trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    }) {
                        IconDelete()
                    }
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        if (trashedPhotos.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.trash_is_empty),
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pinchToZoomColumns({ columnCount }, { columnCount = it })
            ) {
                LazyVerticalGrid(
                    GridCells.Fixed(columnCount.roundToInt().coerceIn(2, 8)),
                    Modifier.padding(paddingValues),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    photosGroupedByMonth.forEach { (month, photosInMonth) ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                month,
                                Modifier.padding(top = 16.dp, bottom = 8.dp, start = 16.dp),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        items(photosInMonth, { it.id }, contentType = { "photo_thumbnail" }) { photo ->
                            val isSelected = photo.id in selectedIds
                            ImageLoader.SelectablePhotoItem(
                                photo = photo,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                onToggleSelection = {
                                    if (isSelected) selectedIds.remove(photo.id) else selectedIds.add(photo.id)
                                },
                                onClick = {
                                    if (isSelectionMode) {
                                        if (isSelected) selectedIds.remove(photo.id) else selectedIds.add(photo.id)
                                    } else {
                                        selectedIds.add(photo.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
