package com.vayunmathur.photos.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.SearchAiState
import com.vayunmathur.photos.util.SecureFolderViewModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant
import com.vayunmathur.library.R as LibraryR

internal fun groupPhotosByMonth(
    photos: List<Photo>,
    resources: android.content.res.Resources,
): Map<String, List<Photo>> {
    return photos.groupBy {
        val date = Instant.fromEpochMilliseconds(it.date).toLocalDateTime(TimeZone.currentSystemDefault())
        LocalDate(date.year, date.month, 1)
    }.toSortedMap(compareByDescending { it }).mapKeys {
        resources.getString(R.string.month_year_format, MonthNames.ENGLISH_ABBREVIATED.names[it.key.month.ordinal], it.key.year)
    }.mapValues { pair -> pair.value.sortedByDescending { it.date } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryPage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val allPhotos by galleryViewModel.photos.collectAsState()
    val photos by remember { derivedStateOf { allPhotos.filter { !it.isTrashed } } }
    val context = LocalContext.current
    var columnCount by LocalColumnCount.current

    val selectedIds by galleryViewModel.selectedIds.collectAsState()
    val isRefreshing by galleryViewModel.isRefreshing.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()

    val searchQuery by galleryViewModel.searchQuery.collectAsState()
    val searchResults by galleryViewModel.searchResults.collectAsState()
    val searchAiState by galleryViewModel.searchAiState.collectAsState()
    val ocrCount by galleryViewModel.ocrCount.collectAsState()
    val ocrTargetCount by galleryViewModel.ocrTargetCount.collectAsState()
    val clipCount by galleryViewModel.clipCount.collectAsState()
    val clipTargetCount by galleryViewModel.clipTargetCount.collectAsState()
    var searchActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        galleryViewModel.runSync()
        galleryViewModel.enqueueSync()
    }

    val mediaResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            galleryViewModel.clearSelection()
            galleryViewModel.runSync()
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
    
    val onMoveToSecureClick: () -> Unit = onMoveToSecureClick@{
        val activity = context as FragmentActivity
        val selectedPhotos = photos.filter { it.id in selectedIds }
        
        secureFolderViewModel.unlock(
            activity,
            onSuccess = { _, _ ->
                secureFolderViewModel.moveToSecure(
                    photos = selectedPhotos,
                    sourcePhotoDao = galleryViewModel.photoDao,
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
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryPage", "MediaStore delete request failed", e)
                        // Fallback: clear selection and refresh anyway
                        galleryViewModel.clearSelection()
                        galleryViewModel.runSync()
                    }
                }
            },
            onFailure = {},
        )
    }

    val onDeleteClick: () -> Unit = {
        val uris = photos.filter { it.id in selectedIds }.map { it.uri.toUri() }
        val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
        mediaResultLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    val resources = LocalResources.current
    val photosGroupedByMonth by remember {
        derivedStateOf { groupPhotosByMonth(photos, resources) }
    }

    val displayPhotos = if (searchActive && searchQuery.isNotEmpty()) searchResults else photos
    val displayPhotosGroupedByMonth by remember(displayPhotos) {
        derivedStateOf { groupPhotosByMonth(displayPhotos, resources) }
    }

    Scaffold(
        topBar = {
            Column {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.items_selected, selectedIds.size)) },
                        navigationIcon = {
                            IconButton(onClick = { galleryViewModel.clearSelection() }) {
                                IconClose()
                            }
                        },
                        actions = {
                            IconButton(onClick = onMoveToSecureClick) {
                                Icon(painterResource(R.drawable.lock_24px), contentDescription = stringResource(R.string.action_move_to_secure))
                            }
                            IconButton(onClick = onDeleteClick) {
                                IconDelete()
                            }
                        }
                    )
                } else {
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { galleryViewModel.setSearchQuery(it) },
                        onSearch = { searchActive = false },
                        active = searchActive,
                        onActiveChange = { searchActive = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        leadingIcon = {
                            IconSearch()
                        },
                        trailingIcon = {
                            if (searchActive) {
                                IconButton(onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        galleryViewModel.setSearchQuery("")
                                    } else {
                                        searchActive = false
                                    }
                                }) {
                                    IconClose()
                                }
                            }
                        }
                    ) {
                        // Search bar expanded content
                        if (searchQuery.isNotEmpty()) {
                            // Semantic search is served by OpenAssistant; if it's
                            // unavailable, tell the user (OCR/filename results, if
                            // any, still show below).
                            val aiMessage = when (searchAiState) {
                                SearchAiState.NOT_INSTALLED -> stringResource(R.string.openassistant_not_found)
                                SearchAiState.NEEDS_UPDATE -> stringResource(R.string.openassistant_needs_update)
                                SearchAiState.DOWNLOADING -> stringResource(R.string.model_downloading_description)
                                SearchAiState.READY -> null
                            }
                            if (aiMessage != null) {
                                ListItem(
                                    headlineContent = { Text(aiMessage) },
                                    leadingContent = { IconSearch() },
                                )
                            }
                            // Show search results as a photo grid
                            LazyVerticalGrid(
                                GridCells.Fixed(3),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxSize().padding(4.dp)
                            ) {
                                items(searchResults, key = { it.id }, contentType = { "photo_thumbnail" }) { photo ->
                                    ImageLoader.PhotoItem(
                                        photo = photo,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                        onClick = {
                                            searchActive = false
                                            backStack.add(Route.PhotoPage(photo.id, searchResults))
                                        }
                                    )
                                }
                            }
                        } else {
                            // Show on-device indexing progress (text + visual).
                            if (ocrTargetCount > 0) {
                                val pct = if (ocrTargetCount > 0) (ocrCount * 100 / ocrTargetCount) else 0
                                ListItem(
                                    headlineContent = { Text("$pct% of photos processed") },
                                    supportingContent = { Text("$ocrCount / $ocrTargetCount photos indexed for text search") },
                                    leadingContent = {
                                        CircularProgressIndicator(
                                            progress = { ocrCount.toFloat() / ocrTargetCount },
                                            modifier = Modifier.size(40.dp),
                                            strokeWidth = 4.dp
                                        )
                                    }
                                )
                            }
                            if (clipTargetCount > 0) {
                                val pct = if (clipTargetCount > 0) (clipCount * 100 / clipTargetCount) else 0
                                ListItem(
                                    headlineContent = { Text("$pct% of photos processed") },
                                    supportingContent = { Text("$clipCount / $clipTargetCount photos indexed for visual search") },
                                    leadingContent = {
                                        CircularProgressIndicator(
                                            progress = { clipCount.toFloat() / clipTargetCount },
                                            modifier = Modifier.size(40.dp),
                                            strokeWidth = 4.dp
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = { if (!isSelectionMode) NavigationBar(Route.Gallery, backStack) }
    ) { paddingValues ->
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { galleryViewModel.runSync() },
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pinchToZoomColumns({ columnCount }, { columnCount = it })
            ) {
                LazyVerticalGrid(
                    GridCells.Fixed(columnCount.roundToInt().coerceIn(2, 8)),
                    Modifier,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val currentGroupedPhotos = if (searchActive && searchQuery.isNotEmpty()) displayPhotosGroupedByMonth else photosGroupedByMonth
                    currentGroupedPhotos.forEach { (month, photosInMonth) ->
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
                                onToggleSelection = { galleryViewModel.toggleSelection(photo.id) },
                                onClick = {
                                    if (isSelectionMode) {
                                        galleryViewModel.toggleSelection(photo.id)
                                    } else {
                                        backStack.add(Route.PhotoPage(photo.id, null))
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

fun Modifier.pinchToZoomColumns(getColumnCount: () -> Float, setColumnCount: (Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        setColumnCount((getColumnCount() / zoom).coerceIn(2f, 8f))
                        event.changes.forEach { it.consume() }
                    }
                }
                if (event.changes.all { it.changedToUp() }) break
            }
        }
    }
