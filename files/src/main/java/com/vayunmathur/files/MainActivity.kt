package com.vayunmathur.files

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.provider.Settings
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconUnarchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                HomeDirectoryPage()
            }
        }
    }
}

@Composable
fun HomeDirectoryPage() {
    val context = LocalContext.current
    var isGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isGranted = Environment.isExternalStorageManager()
    }

    if (!isGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                launcher.launch(intent)
            }) {
                Text("Grant All Files Access")
            }
        }
    } else {
        DirectoryPage(Environment.getExternalStorageDirectory().toOkioPath())
    }
}

val fs = FileSystem.SYSTEM
fun Path.listFiles(): List<Path> = fs.list(this).toList()
val Path.isDirectory: Boolean
    get() = fs.metadataOrNull(this)?.isDirectory ?: false
val Path.size: Long?
    get() = fs.metadataOrNull(this)?.size

fun Path.deleteRecursively() {
    if (isDirectory) {
        listFiles().forEach { it.deleteRecursively() }
    }
    fs.delete(this)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPage(rootFile: Path) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var currentDirectory by remember { mutableStateOf(rootFile) }
    var selectedPaths by remember(currentDirectory) { mutableStateOf(setOf<Path>()) }
    var pathBeingRenamed by remember { mutableStateOf<Path?>(null) }

    val zipToUnzip = remember(selectedPaths) {
        if (selectedPaths.size == 1 && !selectedPaths.first().isDirectory && selectedPaths.first().name.endsWith(".zip", ignoreCase = true)) {
            selectedPaths.first()
        } else null
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && zipToUnzip != null) {
            val path = uri.path?.split(":")?.lastOrNull()?.let {
                Environment.getExternalStorageDirectory().resolve(it).toOkioPath()
            } ?: currentDirectory

            val unzipWork = OneTimeWorkRequestBuilder<UnzipWorker>()
                .setInputData(workDataOf(
                    "zip_path" to zipToUnzip.toString(),
                    "dest_path" to path.toString()
                ))
                .build()
            WorkManager.getInstance(context).enqueue(unzipWork)

            selectedPaths = emptySet()
            scope.launch {
                snackbarHostState.showSnackbar("Unzipping started to ${path.name}")
            }
        }
    }

    // Data state
    var filesList by remember(currentDirectory) {
        mutableStateOf(currentDirectory.listFiles().partition { it.isDirectory })
    }

    fun forceRefresh() {
        filesList = currentDirectory.listFiles().partition { it.isDirectory }
    }

    LaunchedEffect(currentDirectory) {
        val observer = object : FileObserver(currentDirectory.toFile(), FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                forceRefresh()
            }
        }
        observer.startWatching()
        try {
            awaitCancellation()
        } finally {
            observer.stopWatching()
        }
    }

    val (directories, files) = filesList

    val focusManager = LocalFocusManager.current

    val root = remember { Environment.getExternalStorageDirectory().toOkioPath() }
    val breadcrumbs = remember(currentDirectory) {
        val list = mutableListOf<Path>()
        var temp: Path? = currentDirectory
        while (temp != null) {
            list.add(0, temp)
            if (temp == root) break
            temp = temp.parent
        }
        list
    }

    BackHandler(currentDirectory != root || selectedPaths.isNotEmpty()) {
        if (selectedPaths.isNotEmpty()) {
            selectedPaths = emptySet()
            pathBeingRenamed = null
        } else {
            currentDirectory = currentDirectory.parent ?: currentDirectory
        }
    }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                focusManager.clearFocus()
                pathBeingRenamed = null
                selectedPaths = emptySet()
            },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        breadcrumbs.forEachIndexed { index, path ->
                            var isBreadcrumbDraggingOver by remember { mutableStateOf(false) }

                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isBreadcrumbDraggingOver) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else Color.Transparent,
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .dragAndDropTarget(
                                        shouldStartDragAndDrop = { event ->
                                            event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
                                        },
                                        target = remember(path) {
                                            object : DragAndDropTarget {
                                                override fun onDrop(event: DragAndDropEvent): Boolean {
                                                    isBreadcrumbDraggingOver = false
                                                    val dragEvent = event.toAndroidDragEvent()
                                                    val clipData = dragEvent.clipData
                                                    if (clipData != null && clipData.itemCount > 0) {
                                                        val sources = (0 until clipData.itemCount).map {
                                                            clipData.getItemAt(it).text.toString().toPath()
                                                        }
                                                        var movedAny = false
                                                        sources.forEach { sourcePath ->
                                                            if (sourcePath.parent != path && sourcePath != path) {
                                                                try {
                                                                    fs.atomicMove(sourcePath, path.resolve(sourcePath.name))
                                                                    movedAny = true
                                                                } catch (e: Exception) {
                                                                    scope.launch {
                                                                        snackbarHostState.showSnackbar("Move failed: ${e.localizedMessage}")
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        if (movedAny) {
                                                            forceRefresh()
                                                            selectedPaths = emptySet()
                                                            pathBeingRenamed = null
                                                            return true
                                                        }
                                                    }
                                                    return false
                                                }
                                                override fun onEntered(event: DragAndDropEvent) {
                                                    isBreadcrumbDraggingOver = true
                                                }
                                                override fun onExited(event: DragAndDropEvent) {
                                                    isBreadcrumbDraggingOver = false
                                                }
                                                override fun onEnded(event: DragAndDropEvent) {
                                                    isBreadcrumbDraggingOver = false
                                                }
                                            }
                                        }
                                    )
                                    .clickable {
                                        currentDirectory = path
                                    }
                                    .padding(4.dp)
                            ) {
                                Text(
                                    text = if (path == root) Build.MODEL else path.name,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            if (index < breadcrumbs.size - 1) {
                                IconChevronRight(
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (selectedPaths.isNotEmpty()) {
                        IconButton(onClick = {
                            selectedPaths = emptySet()
                            pathBeingRenamed = null
                        }) {
                            IconClose()
                        }
                    }
                    if (zipToUnzip != null) {
                        IconButton(onClick = { treeLauncher.launch(null) }) {
                            IconUnarchive()
                        }
                    }
                    // Show Rename Button if exactly 1 is selected
                    if (selectedPaths.size == 1) {
                        IconButton(onClick = { pathBeingRenamed = selectedPaths.first() }) {
                            IconEdit()
                        }
                    }
                    // Delete Button
                    if (selectedPaths.isNotEmpty()) {
                        IconButton(onClick = {
                            selectedPaths.forEach { it.deleteRecursively() }
                            selectedPaths = emptySet()
                            pathBeingRenamed = null
                            forceRefresh()
                        }) {
                            IconDelete()
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            val allItems = directories.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }

            items(allItems, key = { it.toFile().absolutePath }) { child ->
                val isSelected = selectedPaths.contains(child)
                val isEditing = pathBeingRenamed == child

                DirectoryItem(
                    file = child,
                    isEditing = isEditing,
                    isSelected = isSelected,
                    onRename = { newName ->
                        fs.atomicMove(child, child.parent!!.resolve(newName))
                        forceRefresh()
                        pathBeingRenamed = null
                        selectedPaths = emptySet()
                    },
                    onToggleSelection = {
                        if(pathBeingRenamed != null) pathBeingRenamed = null
                        if (!isSelected) {
                            selectedPaths = selectedPaths + child
                        }
                    },
                    onClick = {
                        if (selectedPaths.isNotEmpty()) {
                            if (isSelected && pathBeingRenamed == child) {
                                pathBeingRenamed = null
                            }
                            selectedPaths = if (isSelected) selectedPaths - child
                            else selectedPaths + child
                        } else if (child.isDirectory) {
                            currentDirectory = child
                        } else {
                            val file = child.toFile()
                            val extension = file.extension
                            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                setDataAndType(uri, mimeType)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("No app found to open this file")
                                }
                            }
                        }
                    },
                    onMove = { sources ->
                        if (child.isDirectory) {
                            var movedAny = false
                            sources.forEach { source ->
                                if (source != child && !child.toString().startsWith(source.toString())) {
                                    try {
                                        fs.atomicMove(source, child.resolve(source.name))
                                        movedAny = true
                                    } catch (e: Exception) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Move failed: ${e.localizedMessage}")
                                        }
                                    }
                                }
                            }
                            if (movedAny) {
                                forceRefresh()
                                selectedPaths = emptySet()
                                pathBeingRenamed = null
                            }
                        }
                    },
                    onStartDrag = {
                        if (selectedPaths.contains(child)) selectedPaths.toList()
                        else listOf(child)
                    }
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDraggingOver) MaterialTheme.colorScheme.primaryContainer
                else if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .dragAndDropSource(
                block = {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            val paths = currentOnStartDrag()
                            val clipData = ClipData.newPlainText("path", paths.first().toString())
                            for (i in 1 until paths.size) {
                                clipData.addItem(ClipData.Item(paths[i].toString()))
                            }

                            startTransfer(
                                DragAndDropTransferData(
                                    clipData = clipData,
                                    flags = View.DRAG_FLAG_GLOBAL
                                )
                            )
                        },
                        onDrag = { _, _ -> },
                        onDragEnd = { },
                        onDragCancel = { }
                    )
                }
            )
            .then(
                if (file.isDirectory) {
                    Modifier.dragAndDropTarget(
                        shouldStartDragAndDrop = { event ->
                            event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
                        },
                        target = remember(file) {
                            object : DragAndDropTarget {
                                override fun onDrop(event: DragAndDropEvent): Boolean {
                                    isDraggingOver = false
                                    val dragEvent = event.toAndroidDragEvent()
                                    val clipData = dragEvent.clipData
                                    if (clipData != null && clipData.itemCount > 0) {
                                        val sources = (0 until clipData.itemCount).map {
                                            clipData.getItemAt(it).text.toString().toPath()
                                        }
                                        currentOnMove(sources)
                                        return true
                                    }
                                    return false
                                }

                                override fun onEntered(event: DragAndDropEvent) {
                                    isDraggingOver = true
                                }

                                override fun onExited(event: DragAndDropEvent) {
                                    isDraggingOver = false
                                }

                                override fun onEnded(event: DragAndDropEvent) {
                                    isDraggingOver = false
                                }
                            }
                        }
                    )
                } else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onToggleSelection
            )
    ) {
        ListItem(
            headlineContent = {
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
                    Text(file.name)
                }
            },
            leadingContent = {
                Icon(
                    if (file.isDirectory) painterResource(R.drawable.folder_24px) else painterResource(R.drawable.docs_24px),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            },
            supportingContent = {
                if (!file.isDirectory) {
                    file.size?.let { size ->
                        Text(byteSizeString(size))
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}

fun byteSizeString(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var unitIdx = 0
    var bytesS = bytes.toDouble()
    while(bytesS >= 1024) {
        bytesS /= 1024
        unitIdx++
    }
    bytesS = (bytesS*100).roundToLong()/100.0
    return "$bytesS ${units[unitIdx]}"
}
