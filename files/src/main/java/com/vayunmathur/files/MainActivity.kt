package com.vayunmathur.files

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.vayunmathur.files.util.FilesViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconArchive
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUnarchive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

class MainActivity : ComponentActivity() {
    private val viewModel: FilesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent { DynamicTheme { HomeDirectoryPage(viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.takeIf { it.type != null } ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { viewModel.setIncomingUris(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { viewModel.setIncomingUris(it) }
        }
    }
}

@Composable
fun HomeDirectoryPage(viewModel: FilesViewModel) {
    val context = LocalContext.current
    val isFilesGranted by viewModel.isFilesGranted.collectAsState()
    val hasPromptedNotifications by viewModel.hasPromptedNotifications.collectAsState()
    var showNotificationDialog by remember { mutableStateOf(false) }

    val filesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshPermissions()
        }

    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            viewModel.setNotificationsPrompted()
            showNotificationDialog = false
        }

    LaunchedEffect(isFilesGranted, hasPromptedNotifications) {
        if (isFilesGranted && !hasPromptedNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                showNotificationDialog = true
            } else {
                viewModel.setNotificationsPrompted()
            }
        }
    }

    if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.setNotificationsPrompted()
                showNotificationDialog = false
            },
            title = { Text(stringResource(R.string.enable_notifications)) },
            text = { Text(stringResource(R.string.notification_permission_rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationsLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    }) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.setNotificationsPrompted()
                        showNotificationDialog = false
                    }) { Text(stringResource(R.string.skip)) }
            })
    }

    if (!isFilesGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(
                onClick = {
                    val intent =
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.fromParts(
                                "package", context.packageName, null
                            )
                        }
                    filesLauncher.launch(intent)
                }) { Text(stringResource(R.string.grant_all_files_access)) }
        }
    } else {
        DirectoryPage(viewModel)
    }
}

val fs = FileSystem.SYSTEM

fun Path.listFiles(fileSystem: FileSystem = fs): List<Path> =
    fileSystem.listOrNull(this) ?: emptyList()

fun Path.isDirectory(fileSystem: FileSystem = fs): Boolean =
    fileSystem.metadataOrNull(this)?.isDirectory ?: false

fun Path.size(fileSystem: FileSystem = fs): Long? = fileSystem.metadataOrNull(this)?.size

fun Path.deleteRecursively(fileSystem: FileSystem = fs) {
    fileSystem.deleteRecursively(this)
}

fun pathAncestors(from: Path?, upTo: Path?): List<Path> = buildList {
    var p = from
    while (p != null) {
        add(0, p)
        if (p == upTo) break
        p = p.parent
    }
}

fun dropTarget(
    onDragStateChange: (Boolean) -> Unit,
    onDrop: (List<Path>) -> Unit
) = object : DragAndDropTarget {
    override fun onDrop(event: DragAndDropEvent): Boolean {
        onDragStateChange(false)
        val clipData = event.toAndroidDragEvent().clipData ?: return false
        if (clipData.itemCount == 0) return false
        onDrop((0 until clipData.itemCount).map { clipData.getItemAt(it).text.toString().toPath() })
        return true
    }
    override fun onEntered(event: DragAndDropEvent) { onDragStateChange(true) }
    override fun onExited(event: DragAndDropEvent) { onDragStateChange(false) }
    override fun onEnded(event: DragAndDropEvent) { onDragStateChange(false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPage(viewModel: FilesViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    val currentFileSystem by viewModel.currentFileSystem.collectAsState()
    val currentDirectory by viewModel.currentDirectory.collectAsState()
    val zipPath by viewModel.zipPath.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val incomingUris by viewModel.incomingUris.collectAsState()

    val isReadOnly = zipPath != null

    // UI-only state (kept in compose)
    var pathBeingRenamed by remember { mutableStateOf<Path?>(null) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var archiveName by remember { mutableStateOf("archive.zip") }

    // Forward VM messages to the local SnackbarHostState.
    LaunchedEffect(snackbarHostState) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Launch ACTION_VIEW intents emitted by the VM (with no-app-found fallback).
    LaunchedEffect(Unit) {
        viewModel.intents.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                viewModel.showMessage(resources.getString(R.string.no_app_found_to_open_file))
            }
        }
    }

    // Reset selection-dependent UI state when the VM's selection clears.
    LaunchedEffect(selectedPaths) {
        if (selectedPaths.isEmpty()) pathBeingRenamed = null
    }

    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text(stringResource(R.string.archive_selection)) },
            text = {
                TextField(
                    value = archiveName,
                    onValueChange = { archiveName = it },
                    label = { Text(stringResource(R.string.zip_file_name_label)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.archive(archiveName)
                        showArchiveDialog = false
                    }) { Text(stringResource(R.string.archive)) }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            })
    }

    val zipToUnzip = remember(selectedPaths, currentFileSystem) {
        selectedPaths.singleOrNull()?.takeIf {
            !it.isDirectory(currentFileSystem) && it.name.endsWith(".zip", ignoreCase = true)
        }
    }

    val treeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null && zipToUnzip != null) {
                val path = uri.path?.split(":")?.lastOrNull()?.let {
                    Environment.getExternalStorageDirectory().resolve(it).toOkioPath()
                } ?: currentDirectory
                viewModel.unzip(zipToUnzip, path)
            }
        }

    val (directories, files) = entries

    val focusManager = LocalFocusManager.current

    val root = viewModel.rootDirectory

    val breadcrumbs = remember(currentDirectory, zipPath, currentFileSystem) {
        if (zipPath == null) {
            pathAncestors(currentDirectory, root)
                .map { Triple(it, fs, if (it == root) Build.MODEL else it.name) }
        } else {
            pathAncestors(zipPath?.parent, root)
                .map { Triple(it, fs, if (it == root) Build.MODEL else it.name) } +
            pathAncestors(currentDirectory, null)
                .map { Triple(it, currentFileSystem, it.name.ifEmpty { zipPath!!.name }) }
        }
    }

    BackHandler(currentDirectory != root || selectedPaths.isNotEmpty() || zipPath != null) {
        pathBeingRenamed = null
        viewModel.handleBack()
    }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) {
                focusManager.clearFocus()
                pathBeingRenamed = null
                viewModel.clearSelection()
            }, snackbarHost = { SnackbarHost(snackbarHostState) }, topBar = {
            TopAppBar(title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    breadcrumbs.forEachIndexed { index, (path, fileSystem, displayName) ->
                        var isBreadcrumbDraggingOver by remember {
                            mutableStateOf(false)
                        }

                        Box(
                            modifier = Modifier
                                .background(
                                    if (isBreadcrumbDraggingOver) MaterialTheme.colorScheme.primaryContainer.copy(
                                        alpha = 0.5f
                                    )
                                    else Color.Transparent, shape = MaterialTheme.shapes.small
                                )
                                .dragAndDropTarget(
                                    shouldStartDragAndDrop = { event ->
                                        !isReadOnly && event.mimeTypes().contains(
                                            ClipDescription.MIMETYPE_TEXT_PLAIN
                                        )
                                    },
                                    target = remember(path, fileSystem) {
                                        dropTarget(
                                            onDragStateChange = { isBreadcrumbDraggingOver = it },
                                            onDrop = { sources -> viewModel.moveToBreadcrumb(sources, path) }
                                        )
                                    })
                                .clickable {
                                    viewModel.navigateTo(path, fileSystem)
                                }
                                .padding(4.dp)) {
                            Text(
                                text = displayName, style = MaterialTheme.typography.titleLarge
                            )
                        }
                        if (index < breadcrumbs.size - 1) {
                            IconChevronRight(tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }, actions = {
                if (selectedPaths.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.clearSelection() }) { IconClose() }
                }
                if (incomingUris != null && !isReadOnly) {
                    IconButton(
                        onClick = { viewModel.saveIncomingUris() }) { IconSave() }
                }
                if (!isReadOnly) {
                    if (selectedPaths.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                archiveName =
                                    if (selectedPaths.size == 1) "${selectedPaths.first().name}.zip"
                                    else "archive.zip"
                                showArchiveDialog = true
                            }) { IconArchive() }
                    }
                    if (zipToUnzip != null) {
                        IconButton(onClick = { treeLauncher.launch(null) }) {
                            IconUnarchive()
                        }
                    }
                    // Show Rename Button if exactly 1 is selected
                    if (selectedPaths.size == 1) {
                        IconButton(
                            onClick = { pathBeingRenamed = selectedPaths.first() }) { IconEdit() }
                    }
                    // Delete Button
                    if (selectedPaths.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.deleteSelection() }) { IconDelete() }
                    }
                }
            })
        }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            val allItems =
                directories.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }

            items(allItems, key = { it.toString() }) { child ->
                val isSelected = selectedPaths.contains(child)
                val isEditing = pathBeingRenamed == child

                DirectoryItem(
                    file = child,
                    isEditing = isEditing,
                    isSelected = isSelected,
                    fileSystem = currentFileSystem,
                    isReadOnly = isReadOnly,
                    onRename = { newName ->
                        pathBeingRenamed = null
                        viewModel.rename(child, newName)
                    },
                    onToggleSelection = {
                        if (isReadOnly) return@DirectoryItem
                        if (pathBeingRenamed != null) pathBeingRenamed = null
                        if (!isSelected) {
                            viewModel.addToSelection(child)
                        }
                    },
                    onClick = {
                        if (selectedPaths.isNotEmpty()) {
                            if (isSelected && pathBeingRenamed == child) {
                                pathBeingRenamed = null
                            }
                            viewModel.toggleSelection(child)
                        } else if (child.isDirectory(currentFileSystem)) {
                            viewModel.navigateTo(child, currentFileSystem)
                        } else if (child.name.endsWith(".zip", ignoreCase = true)) {
                            viewModel.openZipFile(child)
                        } else {
                            viewModel.openFile(child)
                        }
                    },
                    onMove = { sources ->
                        if (!isReadOnly && child.isDirectory(currentFileSystem)) {
                            viewModel.moveInto(sources, child)
                        }
                    },
                    onStartDrag = {
                        if (isReadOnly) emptyList()
                        else if (selectedPaths.contains(child)) selectedPaths.toList()
                        else listOf(child)
                    })
                HorizontalDivider(
                    thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryItem(
    file: Path,
    isEditing: Boolean,
    isSelected: Boolean,
    fileSystem: FileSystem,
    isReadOnly: Boolean,
    onRename: (String) -> Unit,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onMove: (List<Path>) -> Unit,
    onStartDrag: () -> List<Path>
) {
    var editedName by remember(isEditing) { mutableStateOf(file.name) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var isDraggingOver by remember { mutableStateOf(false) }
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnStartDrag by rememberUpdatedState(onStartDrag)

    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDraggingOver) MaterialTheme.colorScheme.primaryContainer
                else if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .dragAndDropSource { _ ->
                val paths = currentOnStartDrag()
                if (paths.isEmpty()) return@dragAndDropSource null

                val uris = paths.map { path ->
                    FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", path.toFile()
                    )
                }
                val mimeTypes = paths.map { path ->
                    val extension = path.name.substringAfterLast(
                        '.', ""
                    )
                    if (extension == "md") "text/markdown"
                    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        extension
                    ) ?: "*/*"
                }.toMutableList().apply {
                    add(
                        ClipDescription.MIMETYPE_TEXT_PLAIN
                    )
                }.distinct().toTypedArray()

                val clipData = ClipData(
                    paths.first().name, mimeTypes, ClipData.Item(
                        paths.first().toString(), null, null, uris.first()
                    )
                )
                for (i in 1 until uris.size) {
                    clipData.addItem(
                        ClipData.Item(
                            paths[i].toString(), null, null, uris[i]
                        )
                    )
                }

                DragAndDropTransferData(
                    clipData = clipData,
                    flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                )
            }
            .then(
                if (file.isDirectory(fileSystem) && !isReadOnly) {
                    Modifier.dragAndDropTarget(shouldStartDragAndDrop = { event ->
                        event.mimeTypes().contains(
                            ClipDescription.MIMETYPE_TEXT_PLAIN
                        )
                    }, target = remember(file) {
                        dropTarget(
                            onDragStateChange = { isDraggingOver = it },
                            onDrop = { currentOnMove(it) }
                        )
                    })
                } else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onToggleSelection)
    ) {
        ListItem(
            content = {
                if (isEditing) {
                    TextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onRename(editedName) })
                    )
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                } else {
                    Text(file.name.ifEmpty { "/" })
                }
            }, leadingContent = {
                Icon(
                    if (file.isDirectory(fileSystem)) painterResource(R.drawable.folder_24px)
                    else painterResource(R.drawable.docs_24px),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }, supportingContent = {
                if (!file.isDirectory(fileSystem)) {
                    file.size(fileSystem)?.let { size -> Text(Formatter.formatShortFileSize(context, size)) }
                }
            }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
