package com.vayunmathur.photos.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconUnarchive
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.room.SqlCipherDbCodec
import java.io.File
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.ui.LocalColumnCount
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.platform.SecureFolderViewModel
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
    var showRelockSettings by remember { mutableStateOf(false) }
    val relockOnExit by secureFolderViewModel.relockOnExit.collectAsState()

    val closeSelection: (() -> Unit)? =
        if (isSelectionMode) ({ secureFolderViewModel.clearSelection() }) else null
    if (showRelockSettings) {
        SecureFolderRelockDialog(
            relockOnExit = relockOnExit,
            onCheckedChange = { secureFolderViewModel.setRelockOnExit(it) },
            onDismiss = { showRelockSettings = false },
        )
    }
    AppScaffold(
        title = if (isSelectionMode) {
            pluralStringResource(R.plurals.items_selected, selectedIds.size, selectedIds.size)
        } else {
            stringResource(R.string.label_secure_folder)
        },
        onNavigateBack = if (isSelectionMode) null else ({ backStack.pop() }),
        onClose = closeSelection,
        actions = {
            if (isSelectionMode) {
                IconButton(onClick = {
                    val selectedPhotos = photos.filter { it.id in selectedIds }
                    secureFolderViewModel.restorePhotos(selectedPhotos)
                }) {
                    IconUnarchive()
                }
            } else {
                IconButton(onClick = { showRelockSettings = true }) {
                    IconSettings()
                }
                BackupButtons(
                    dbConfigs = listOf("vault-db" to password),
                    dbCodec = SqlCipherDbCodec,
                    extraFiles = listOf(File(context.filesDir, "secure_vault"))
                )
            }
        },
    ) { paddingValues ->
        if (photos.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.secure_folder_is_empty),
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
                            onClick = {
                                if (isSelectionMode) {
                                    secureFolderViewModel.toggleSelection(photo.id)
                                } else {
                                    backStack.add(Route.VaultViewer(photo.id))
                                }
                            },
                            onLongClick = {
                                secureFolderViewModel.addSelection(photo.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecureFolderRelockDialog(
    relockOnExit: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.secure_folder_relock_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.secure_folder_relock_label))
                    Switch(checked = relockOnExit, onCheckedChange = onCheckedChange)
                }
                Text(
                    stringResource(R.string.secure_folder_relock_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.done))
            }
        },
    )
}

