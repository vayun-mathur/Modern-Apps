package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.AppScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.ui.LocalColumnCount
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.platform.pushPhoto
import com.vayunmathur.photos.platform.Album
import com.vayunmathur.photos.platform.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The photo grid for one album, with no dependency on the ViewModel so it can
 * be rendered from a `@Preview`. Selection enables per-photo actions (remove
 * from album, set as cover); the overflow menu holds album-level actions
 * (rename, delete). All mutations are performed by the caller through MediaStore.
 *
 * The grid itself matches the gallery/trash grids: pinch-to-zoom column count
 * (shared with every other grid through [LocalColumnCount]), month grouping
 * and pull-to-refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    backStack: NavBackStack<Route>,
    albumName: String,
    album: Album?,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelection: (Long) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onRemoveFromAlbum: () -> Unit = {},
    onSetCover: (Long) -> Unit = {},
    onRenameAlbum: (String) -> Unit = {},
    onDeleteAlbum: () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    /**
     * How a grid tile paints its photo. Previews pass a placeholder, because
     * Layoutlib has neither MediaStore nor the [ImageLoader] singleton
     * MainActivity initialises.
     */
    thumbnail: @Composable (Photo, Modifier) -> Unit = { photo, modifier -> ImageLoader.PhotoItem(photo, modifier) },
) {
    val isUnknown = albumName == Album.UNKNOWN_NAME
    val title = if (isUnknown) stringResource(R.string.albums_unknown) else albumName
    val isSelectionMode = selectedIds.isNotEmpty()
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var columnCount by LocalColumnCount.current

    val photos = album?.photos.orEmpty()
    val resources = LocalResources.current

    // Grouping is O(N) with a timezone conversion per photo, so it runs on
    // Dispatchers.Default. The seed is computed once so the grid isn't blank on the
    // first frame; produceState's initialValue is eagerly evaluated, hence the
    // remember rather than an inline call.
    val initialGrouping = remember { groupPhotosByMonth(photos, resources) }
    val photosGroupedByMonth by produceState(
        initialValue = initialGrouping,
        photos,
        resources,
    ) {
        value = withContext(Dispatchers.Default) { groupPhotosByMonth(photos, resources) }
    }

    AppScaffold(
        title = if (isSelectionMode) pluralStringResource(R.plurals.items_selected, selectedIds.size, selectedIds.size) else title,
        onNavigateBack = if (isSelectionMode) null else ({ backStack.pop() }),
        onClose = if (isSelectionMode) ({ onClearSelection() }) else null,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        actions = {
            if (isSelectionMode) {
                if (selectedIds.size == 1) {
                    IconButton(onClick = { onSetCover(selectedIds.first()) }) {
                        IconImage()
                    }
                }
                IconButton(onClick = { onRemoveFromAlbum() }) {
                    IconAlbum()
                }
            } else if (!isUnknown) {
                IconButton(onClick = { menuExpanded = true }) {
                    IconMoreVert()
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_rename)) },
                        leadingIcon = { IconEdit() },
                        onClick = {
                            menuExpanded = false
                            showRenameDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_delete)) },
                        leadingIcon = { IconDelete() },
                        onClick = {
                            menuExpanded = false
                            showDeleteDialog = true
                        },
                    )
                }
            }
        },
    ) { padding ->
        if (photos.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.albums_empty),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .pinchToZoomColumns({ columnCount }, { columnCount = it })
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columnCount.roundToInt().coerceIn(2, 8)),
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        photosGroupedByMonth.forEach { (month, photosInMonth) ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    month,
                                    Modifier.padding(top = 16.dp, bottom = 8.dp, start = 16.dp),
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            items(photosInMonth, key = { it.id }, contentType = { "photo_thumbnail" }) { photo ->
                                ImageLoader.SelectablePhotoItem(
                                    photo = photo,
                                    isSelected = photo.id in selectedIds,
                                    isSelectionMode = isSelectionMode,
                                    onToggleSelection = { onToggleSelection(photo.id) },
                                    onClick = {
                                        if (isSelectionMode) {
                                            onToggleSelection(photo.id)
                                        } else {
                                            backStack.pushPhoto(Route.PhotoPage(photo.id, com.vayunmathur.photos.domain.PhotoSource.Album(albumName)))
                                        }
                                    },
                                    thumbnail = thumbnail,
                                    sharedKey = photo.id,
                                )
                            }
                        }
                    }
                }
        }
    }

    if (showRenameDialog) {
        AlbumNameDialog(
            titleRes = R.string.album_rename,
            initialValue = albumName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRenameAlbum(newName)
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.album_delete)) },
            text = { Text(stringResource(R.string.album_delete_confirm, title)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleteAlbum()
                }) {
                    Text(stringResource(R.string.album_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AlbumNameDialog(
    titleRes: Int,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.album_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.album_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
