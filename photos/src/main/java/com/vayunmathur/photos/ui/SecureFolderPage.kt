package com.vayunmathur.photos.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconUnarchive
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.room.SqlCipherDbCodec
import java.io.File
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.util.SecureFolderViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureFolderPage(
    backStack: NavBackStack<Route>,
    password: String,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val photos by secureFolderViewModel.photos.collectAsState()
    val context = LocalContext.current
    var columnCount by LocalColumnCount.current
    val selectedIds by secureFolderViewModel.selectedIds.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(stringResource(R.string.items_selected, selectedIds.size))
                    } else {
                        Text(stringResource(R.string.label_secure_folder))
                    }
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { secureFolderViewModel.clearSelection() }) {
                            IconClose()
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            val selectedPhotos = photos.filter { it.id in selectedIds }
                            secureFolderViewModel.restorePhotos(selectedPhotos)
                        }) {
                            IconUnarchive()
                        }
                    } else {
                        BackupButtons(
                            dbConfigs = listOf("vault-db" to password),
                            dbCodec = SqlCipherDbCodec,
                            extraFiles = listOf(File(context.filesDir, "secure_vault"))
                        )
                    }
                }
            )
        },
        bottomBar = { if (!isSelectionMode) NavigationBar(Route.SecureFolder, backStack) }
    ) { paddingValues ->
        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("Secure Folder is empty", color = Color.Gray)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pinchToZoomColumns({ columnCount }, { columnCount = it })
            ) {
                LazyVerticalGrid(
                    GridCells.Fixed(columnCount.roundToInt().coerceIn(2, 8)),
                    Modifier.padding(paddingValues).fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(photos, { it.id }) { photo ->
                        VaultPhotoItem(
                            photo = photo,
                            password = password,
                            isSelected = photo.id in selectedIds,
                            isSelectionMode = isSelectionMode,
                            secureFolderViewModel = secureFolderViewModel,
                        ) {
                            if (isSelectionMode) {
                                secureFolderViewModel.toggleSelection(photo.id)
                            } else {
                                secureFolderViewModel.addSelection(photo.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultPhotoItem(
    photo: VaultPhoto,
    password: String,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    secureFolderViewModel: SecureFolderViewModel,
    onClick: () -> Unit,
) {
    val thumbnails by secureFolderViewModel.thumbnails.collectAsState()
    val bitmap = thumbnails[photo.thumbnailPath]

    LaunchedEffect(photo.thumbnailPath) {
        secureFolderViewModel.requestThumbnail(photo.thumbnailPath, password)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.DarkGray))
        }

        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = Alignment.TopStart
            ) {
                if (isSelected) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    ) {
                        IconCheck(tint = Color.White)
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.3f),
                        modifier = Modifier.size(24.dp)
                    ) {
                    }
                }
            }
        }
    }
}
