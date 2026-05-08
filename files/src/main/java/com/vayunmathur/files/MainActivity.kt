package com.vayunmathur.files

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.provider.Settings
import android.provider.OpenableColumns
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

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.files.util.UnzipWorker
import com.vayunmathur.files.util.ZipWorker
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconArchive
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUnarchive
import kotlin.math.roundToLong
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.openZip
import okio.source

class MainActivity : ComponentActivity() {
    private var incomingUris by mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent { DynamicTheme { FilesMainScreen(incomingUris) { incomingUris = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                incomingUris = listOf(uri)
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (uris != null) {
                incomingUris = uris
            }
        }
    }
}

@Composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesMainScreen(incomingUris: List<Uri>? = null, onClearIncoming: () -> Unit = {}) {
    var selectedTab by remember { mutableStateOf(0) }
    var currentBrowseDirectory by remember { mutableStateOf<Path?>(null) }
    
    if (currentBrowseDirectory != null) {
        DirectoryPage(
            rootFile = currentBrowseDirectory!!,
            incomingUris = incomingUris,
            onClearIncoming = onClearIncoming,
            onBack = { currentBrowseDirectory = null }
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.CleaningServices, contentDescription = "Clean") },
                    label = { Text("Clean") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Browse") },
                    label = { Text("Browse") }
                )
            }
        }
    ) { paddingValues ->
        if (selectedTab == 0) {
            CleanTab(Modifier.padding(paddingValues))
        } else {
            BrowseTab(Modifier.padding(paddingValues), onNavigate = { currentBrowseDirectory = it })
        }
    }
}

@Composable
fun CleanTab(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(200.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { 0.75f },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 16.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("96 GB", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Text("Used", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("Your storage is looking good.", style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
fun BrowseTab(modifier: Modifier = Modifier, onNavigate: (Path) -> Unit) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text("Categories", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CategoryItem(Icons.Default.Image, "Images")
                CategoryItem(Icons.Default.VideoFile, "Videos")
                CategoryItem(Icons.Default.AudioFile, "Audio")
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CategoryItem(Icons.Default.InsertDriveFile, "Documents")
                CategoryItem(Icons.Default.Apps, "Apps")
                Spacer(Modifier.weight(1f))
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Storage devices", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            ListItem(
                headlineContent = { Text("Internal storage") },
                leadingContent = { Icon(Icons.Default.Smartphone, contentDescription = null) },
                modifier = Modifier.clickable { onNavigate(Environment.getExternalStorageDirectory().toOkioPath()) }
            )
        }
    }
}

@Composable
fun RowScope.CategoryItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(
        modifier = Modifier.weight(1f).padding(8.dp).clip(RoundedCornerShape(12.dp)).clickable { }.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

fun HomeDirectoryPage(incomingUris: List<Uri>? = null, onClearIncoming: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("files_prefs", Context.MODE_PRIVATE) }

    var isFilesGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var hasPromptedNotifications by remember {
        mutableStateOf(prefs.getBoolean("has_prompted_notifications", false))
    }
    var showNotificationDialog by remember { mutableStateOf(false) }

    val filesLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                isFilesGranted = Environment.isExternalStorageManager()
            }

    val notificationsLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    isGranted ->
                prefs.edit().putBoolean("has_prompted_notifications", true).apply()
                hasPromptedNotifications = true
                showNotificationDialog = false
            }

    LaunchedEffect(isFilesGranted, hasPromptedNotifications) {
        if (isFilesGranted &&
                        !hasPromptedNotifications &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            val isGranted =
                    ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                showNotificationDialog = true
            } else {
                prefs.edit().putBoolean("has_prompted_notifications", true).apply()
                hasPromptedNotifications = true
            }
        }
    }

    if (showNotificationDialog) {
        AlertDialog(
                onDismissRequest = {
                    prefs.edit().putBoolean("has_prompted_notifications", true).apply()
                    hasPromptedNotifications = true
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
                            }
                    ) { Text(stringResource(R.string.enable)) }
                },
                dismissButton = {
                    TextButton(
                            onClick = {
                                prefs.edit().putBoolean("has_prompted_notifications", true).apply()
                                hasPromptedNotifications = true
                                showNotificationDialog = false
                            }
                    ) { Text(stringResource(R.string.skip)) }
                }
        )
    }

    if (!isFilesGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(
                    onClick = {
                        val intent =
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        .apply {
                                            data =
                                                    Uri.fromParts(
                                                            "package",
                                                            context.packageName,
                                                            null
                                                    )
                                        }
                        filesLauncher.launch(intent)
                    }
            ) { Text(stringResource(R.string.grant_all_files_access)) }
        }
    } else {
        DirectoryPage(Environment.getExternalStorageDirectory().toOkioPath(), incomingUris, onClearIncoming)
    }
}

val fs = FileSystem.SYSTEM

fun Path.listFiles(fileSystem: FileSystem = fs): List<Path> =
        try {
            fileSystem.list(this).toList()
        } catch (e: Exception) {
            emptyList()
        }

fun Path.isDirectory(fileSystem: FileSystem = fs): Boolean =
        fileSystem.metadataOrNull(this)?.isDirectory ?: false

fun Path.size(fileSystem: FileSystem = fs): Long? = fileSystem.metadataOrNull(this)?.size

fun Path.deleteRecursively(fileSystem: FileSystem = fs) {
    if (isDirectory(fileSystem)) {
        listFiles(fileSystem).forEach { it.deleteRecursively(fileSystem) }
    }
    fileSystem.delete(this)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPage(
        rootFile: Path,
        incomingUris: List<Uri>? = null,
        onClearIncoming: () -> Unit = {},
        onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var currentFileSystem by remember { mutableStateOf<FileSystem>(fs) }
    var currentDirectory by remember { mutableStateOf(rootFile) }
    var zipPath by remember { mutableStateOf<Path?>(null) }

    val isReadOnly = zipPath != null

    BackHandler {
        if (currentDirectory != rootFile) {
            currentDirectory = currentDirectory.parent ?: rootFile
        } else {
            onBack?.invoke()
        }
    }

    var selectedPaths by
            remember(currentDirectory, currentFileSystem) { mutableStateOf(setOf<Path>()) }
    var pathBeingRenamed by remember { mutableStateOf<Path?>(null) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var archiveName by remember { mutableStateOf("archive.zip") }

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
                                val destPath =
                                        currentDirectory.resolve(
                                                if (archiveName.endsWith(".zip")) archiveName
                                                else "$archiveName.zip"
                                        )
                                val zipWork =
                                        OneTimeWorkRequestBuilder<ZipWorker>()
                                                .setInputData(
                                                        workDataOf(
                                                                "source_paths" to
                                                                        selectedPaths
                                                                                .map {
                                                                                    it.toString()
                                                                                }
                                                                                .toTypedArray(),
                                                                "dest_path" to destPath.toString()
                                                        )
                                                )
                                                .build()
                                WorkManager.getInstance(context).enqueue(zipWork)
                                selectedPaths = emptySet()
                                pathBeingRenamed = null
                                showArchiveDialog = false
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                            context.getString(R.string.archiving_started)
                                    )
                                }
                            }
                    ) { Text(stringResource(R.string.archive)) }
                },
                dismissButton = {
                    TextButton(onClick = { showArchiveDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
        )
    }

    val zipToUnzip =
            remember(selectedPaths) {
                if (selectedPaths.size == 1 &&
                                !selectedPaths.first().isDirectory(currentFileSystem) &&
                                selectedPaths.first().name.endsWith(".zip", ignoreCase = true)
                ) {
                    selectedPaths.first()
                } else null
            }

    val treeLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null && zipToUnzip != null) {
                    val path =
                            uri.path?.split(":")?.lastOrNull()?.let {
                                Environment.getExternalStorageDirectory().resolve(it).toOkioPath()
                            }
                                    ?: currentDirectory

                    val unzipWork =
                            OneTimeWorkRequestBuilder<UnzipWorker>()
                                    .setInputData(
                                            workDataOf(
                                                    "zip_path" to zipToUnzip.toString(),
                                                    "dest_path" to path.toString()
                                            )
                                    )
                                    .build()
                    WorkManager.getInstance(context).enqueue(unzipWork)

                    selectedPaths = emptySet()
                    scope.launch {
                        snackbarHostState.showSnackbar(
                                context.getString(R.string.unzipping_started_to, path.name)
                        )
                    }
                }
            }

    // Data state
    var filesList by
            remember(currentDirectory, currentFileSystem) {
                mutableStateOf(
                        currentDirectory.listFiles(currentFileSystem).partition {
                            it.isDirectory(currentFileSystem)
                        }
                )
            }

    fun forceRefresh() {
        filesList =
                currentDirectory.listFiles(currentFileSystem).partition {
                    it.isDirectory(currentFileSystem)
                }
    }

    LaunchedEffect(currentDirectory, currentFileSystem) {
        if (currentFileSystem == fs) {
            val observer =
                    object :
                            FileObserver(
                                    currentDirectory.toFile(),
                                    FileObserver.CREATE or
                                            FileObserver.DELETE or
                                            FileObserver.MOVED_FROM or
                                            FileObserver.MOVED_TO
                            ) {
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
    }

    val (directories, files) = filesList

    val focusManager = LocalFocusManager.current

    val root = remember { Environment.getExternalStorageDirectory().toOkioPath() }

    val breadcrumbs =
            remember(currentDirectory, zipPath, currentFileSystem) {
                if (zipPath == null) {
                    val list = mutableListOf<Path>()
                    var temp: Path? = currentDirectory
                    while (temp != null) {
                        list.add(0, temp)
                        if (temp == root) break
                        temp = temp.parent
                    }
                    list.map { Triple(it, fs, if (it == root) Build.MODEL else it.name) }
                } else {
                    val systemList = mutableListOf<Path>()
                    var tempSystem: Path? = zipPath?.parent
                    while (tempSystem != null) {
                        systemList.add(0, tempSystem)
                        if (tempSystem == root) break
                        tempSystem = tempSystem.parent
                    }

                    val zipList = mutableListOf<Path>()
                    var tempZip: Path? = currentDirectory
                    while (tempZip != null) {
                        zipList.add(0, tempZip)
                        tempZip = tempZip.parent
                    }

                    systemList.map { Triple(it, fs, if (it == root) Build.MODEL else it.name) } +
                            zipList.map {
                                Triple(
                                        it,
                                        currentFileSystem,
                                        if (it.name.isEmpty()) zipPath!!.name else it.name
                                )
                            }
                }
            }

    BackHandler(currentDirectory != root || selectedPaths.isNotEmpty() || zipPath != null) {
        if (selectedPaths.isNotEmpty()) {
            selectedPaths = emptySet()
            pathBeingRenamed = null
        } else if (zipPath != null) {
            if (currentDirectory.toString() == "/" || currentDirectory.name.isEmpty()) {
                currentFileSystem = fs
                currentDirectory = zipPath!!.parent ?: root
                zipPath = null
            } else {
                currentDirectory = currentDirectory.parent ?: "/".toPath()
            }
        } else {
            currentDirectory = currentDirectory.parent ?: currentDirectory
        }
    }

    Scaffold(
            modifier =
                    Modifier.imePadding().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                            ) {
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
                                breadcrumbs.forEachIndexed { index, (path, fileSystem, displayName)
                                    ->
                                    var isBreadcrumbDraggingOver by remember {
                                        mutableStateOf(false)
                                    }

                                    Box(
                                            modifier =
                                                    Modifier.background(
                                                                    if (isBreadcrumbDraggingOver)
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .primaryContainer
                                                                                    .copy(
                                                                                            alpha =
                                                                                                    0.5f
                                                                                    )
                                                                    else Color.Transparent,
                                                                    shape =
                                                                            MaterialTheme.shapes
                                                                                    .small
                                                            )
                                                            .dragAndDropTarget(
                                                                    shouldStartDragAndDrop = { event
                                                                        ->
                                                                        !isReadOnly &&
                                                                                event.mimeTypes()
                                                                                        .contains(
                                                                                                ClipDescription
                                                                                                        .MIMETYPE_TEXT_PLAIN
                                                                                        )
                                                                    },
                                                                    target =
                                                                            remember(
                                                                                    path,
                                                                                    fileSystem
                                                                            ) {
                                                                                object :
                                                                                        DragAndDropTarget {
                                                                                    override fun onDrop(
                                                                                            event:
                                                                                                    DragAndDropEvent
                                                                                    ): Boolean {
                                                                                        isBreadcrumbDraggingOver =
                                                                                                false
                                                                                        val dragEvent =
                                                                                                event.toAndroidDragEvent()
                                                                                        val clipData =
                                                                                                dragEvent
                                                                                                        .clipData
                                                                                        if (clipData !=
                                                                                                        null &&
                                                                                                        clipData.itemCount >
                                                                                                                0
                                                                                        ) {
                                                                                            val sources =
                                                                                                    (0 until
                                                                                                                    clipData.itemCount)
                                                                                                            .map {
                                                                                                                clipData.getItemAt(
                                                                                                                                it
                                                                                                                        )
                                                                                                                        .text
                                                                                                                        .toString()
                                                                                                                        .toPath()
                                                                                                            }
                                                                                            var movedAny =
                                                                                                    false
                                                                                            sources
                                                                                                    .forEach {
                                                                                                            sourcePath
                                                                                                        ->
                                                                                                        if (sourcePath
                                                                                                                        .parent !=
                                                                                                                        path &&
                                                                                                                        sourcePath !=
                                                                                                                                path
                                                                                                        ) {
                                                                                                            try {
                                                                                                                fs.atomicMove(
                                                                                                                        sourcePath,
                                                                                                                        path.resolve(
                                                                                                                                sourcePath
                                                                                                                                        .name
                                                                                                                        )
                                                                                                                )
                                                                                                                movedAny =
                                                                                                                        true
                                                                                                            } catch (
                                                                                                                    e:
                                                                                                                            Exception) {
                                                                                                                scope
                                                                                                                        .launch {
                                                                                                                            snackbarHostState
                                                                                                                                    .showSnackbar(
                                                                                                                                            context.getString(
                                                                                                                                                    R.string
                                                                                                                                                            .move_failed,
                                                                                                                                                    e.localizedMessage
                                                                                                                                            )
                                                                                                                                    )
                                                                                                                        }
                                                                                                            }
                                                                                                        }
                                                                                                    }
                                                                                            if (movedAny
                                                                                            ) {
                                                                                                forceRefresh()
                                                                                                selectedPaths =
                                                                                                        emptySet()
                                                                                                pathBeingRenamed =
                                                                                                        null
                                                                                                return true
                                                                                            }
                                                                                        }
                                                                                        return false
                                                                                    }
                                                                                    override fun onEntered(
                                                                                            event:
                                                                                                    DragAndDropEvent
                                                                                    ) {
                                                                                        isBreadcrumbDraggingOver =
                                                                                                true
                                                                                    }
                                                                                    override fun onExited(
                                                                                            event:
                                                                                                    DragAndDropEvent
                                                                                    ) {
                                                                                        isBreadcrumbDraggingOver =
                                                                                                false
                                                                                    }
                                                                                    override fun onEnded(
                                                                                            event:
                                                                                                    DragAndDropEvent
                                                                                    ) {
                                                                                        isBreadcrumbDraggingOver =
                                                                                                false
                                                                                    }
                                                                                }
                                                                            }
                                                            )
                                                            .clickable {
                                                                currentFileSystem = fileSystem
                                                                currentDirectory = path
                                                                if (fileSystem == fs) zipPath = null
                                                            }
                                                            .padding(4.dp)
                                    ) {
                                        Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.titleLarge
                                        )
                                    }
                                    if (index < breadcrumbs.size - 1) {
                                        IconChevronRight(tint = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        },
                        actions = {
                            if (selectedPaths.isNotEmpty()) {
                                IconButton(
                                        onClick = {
                                            selectedPaths = emptySet()
                                            pathBeingRenamed = null
                                        }
                                ) { IconClose() }
                            }
                            if (incomingUris != null && !isReadOnly) {
                                IconButton(
                                        onClick = {
                                            scope.launch {
                                                incomingUris.forEach { uri ->
                                                    saveUriToPath(context, uri, currentDirectory)
                                                }
                                                onClearIncoming()
                                                forceRefresh()
                                                snackbarHostState.showSnackbar(
                                                        context.getString(R.string.files_saved)
                                                )
                                            }
                                        }
                                ) { IconSave() }
                            }
                            if (!isReadOnly) {
                                if (selectedPaths.isNotEmpty()) {
                                    IconButton(
                                            onClick = {
                                                archiveName =
                                                        if (selectedPaths.size == 1)
                                                                "${selectedPaths.first().name}.zip"
                                                        else "archive.zip"
                                                showArchiveDialog = true
                                            }
                                    ) { IconArchive() }
                                }
                                if (zipToUnzip != null) {
                                    IconButton(onClick = { treeLauncher.launch(null) }) {
                                        IconUnarchive()
                                    }
                                }
                                // Show Rename Button if exactly 1 is selected
                                if (selectedPaths.size == 1) {
                                    IconButton(
                                            onClick = { pathBeingRenamed = selectedPaths.first() }
                                    ) { IconEdit() }
                                }
                                // Delete Button
                                if (selectedPaths.isNotEmpty()) {
                                    IconButton(
                                            onClick = {
                                                selectedPaths.forEach {
                                                    it.deleteRecursively(currentFileSystem)
                                                }
                                                selectedPaths = emptySet()
                                                pathBeingRenamed = null
                                                forceRefresh()
                                            }
                                    ) { IconDelete() }
                                }
                            }
                        }
                )
            }
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            val allItems =
                    directories.sortedBy { it.name.lowercase() } +
                            files.sortedBy { it.name.lowercase() }

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
                            currentFileSystem.atomicMove(child, child.parent!!.resolve(newName))
                            forceRefresh()
                            pathBeingRenamed = null
                            selectedPaths = emptySet()
                        },
                        onToggleSelection = {
                            if (isReadOnly) return@DirectoryItem
                            if (pathBeingRenamed != null) pathBeingRenamed = null
                            if (!isSelected) {
                                selectedPaths = selectedPaths + child
                            }
                        },
                        onClick = {
                            if (selectedPaths.isNotEmpty()) {
                                if (isSelected && pathBeingRenamed == child) {
                                    pathBeingRenamed = null
                                }
                                selectedPaths =
                                        if (isSelected) selectedPaths - child
                                        else selectedPaths + child
                            } else if (child.isDirectory(currentFileSystem)) {
                                currentDirectory = child
                            } else if (child.name.endsWith(".zip", ignoreCase = true)) {
                                try {
                                    val zipFs = currentFileSystem.openZip(child)
                                    currentFileSystem = zipFs
                                    zipPath = if (zipPath == null) child else zipPath
                                    currentDirectory = "/".toPath()
                                } catch (e: Exception) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                                context.getString(
                                                        R.string.could_not_open_zip,
                                                        e.localizedMessage
                                                )
                                        )
                                    }
                                }
                            } else if (currentFileSystem == fs) {
                                val file = child.toFile()
                                val extension = file.extension
                                val mimeType =
                                        MimeTypeMap.getSingleton()
                                                .getMimeTypeFromExtension(extension)
                                                ?: "*/*"

                                val intent =
                                        Intent(Intent.ACTION_VIEW).apply {
                                            val uri =
                                                    FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.fileprovider",
                                                            file
                                                    )
                                            setDataAndType(uri, mimeType)
                                            flags =
                                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        }
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                                context.getString(
                                                        R.string.no_app_found_to_open_file
                                                )
                                        )
                                    }
                                }
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                            context.getString(R.string.zip_browse_only)
                                    )
                                }
                            }
                        },
                        onMove = { sources ->
                            if (!isReadOnly && child.isDirectory(currentFileSystem)) {
                                var movedAny = false
                                sources.forEach { source ->
                                    if (source != child &&
                                                    !child.toString().startsWith(source.toString())
                                    ) {
                                        try {
                                            currentFileSystem.atomicMove(
                                                    source,
                                                    child.resolve(source.name)
                                            )
                                            movedAny = true
                                        } catch (e: Exception) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                        context.getString(
                                                                R.string.move_failed,
                                                                e.localizedMessage
                                                        )
                                                )
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
                            if (isReadOnly) emptyList()
                            else if (selectedPaths.contains(child)) selectedPaths.toList()
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
            modifier =
                    Modifier.fillMaxWidth()
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
                                                    if (paths.isEmpty())
                                                            return@detectDragGesturesAfterLongPress

                                                    val uris =
                                                            paths.map { path ->
                                                                FileProvider.getUriForFile(
                                                                        context,
                                                                        "${context.packageName}.fileprovider",
                                                                        path.toFile()
                                                                )
                                                            }
                                                    val mimeTypes =
                                                            paths.map { path ->
                                                                        val extension =
                                                                                path.name
                                                                                        .substringAfterLast(
                                                                                                '.',
                                                                                                ""
                                                                                        )
                                                                        if (extension == "md")
                                                                                "text/markdown"
                                                                        else
                                                                                MimeTypeMap.getSingleton()
                                                                                        .getMimeTypeFromExtension(
                                                                                                extension
                                                                                        ) ?: "*/*"
                                                                    }
                                                                    .toMutableList()
                                                                    .apply {
                                                                        add(
                                                                                ClipDescription
                                                                                        .MIMETYPE_TEXT_PLAIN
                                                                        )
                                                                    }
                                                                    .distinct()
                                                                    .toTypedArray()

                                                    val clipData =
                                                            ClipData(
                                                                    paths.first().name,
                                                                    mimeTypes,
                                                                    ClipData.Item(
                                                                            paths.first()
                                                                                    .toString(),
                                                                            null,
                                                                            null,
                                                                            uris.first()
                                                                    )
                                                            )
                                                    for (i in 1 until uris.size) {
                                                        clipData.addItem(
                                                                ClipData.Item(
                                                                        paths[i].toString(),
                                                                        null,
                                                                        null,
                                                                        uris[i]
                                                                )
                                                        )
                                                    }

                                                    startTransfer(
                                                            DragAndDropTransferData(
                                                                    clipData = clipData,
                                                                    flags =
                                                                            View.DRAG_FLAG_GLOBAL or
                                                                                    View
                                                                                            .DRAG_FLAG_GLOBAL_URI_READ
                                                            )
                                                    )
                                                },
                                                onDrag = { _, _ -> },
                                                onDragEnd = {},
                                                onDragCancel = {}
                                        )
                                    }
                            )
                            .then(
                                    if (file.isDirectory(fileSystem) && !isReadOnly) {
                                        Modifier.dragAndDropTarget(
                                                shouldStartDragAndDrop = { event ->
                                                    event.mimeTypes()
                                                            .contains(
                                                                    ClipDescription
                                                                            .MIMETYPE_TEXT_PLAIN
                                                            )
                                                },
                                                target =
                                                        remember(file) {
                                                            object : DragAndDropTarget {
                                                                override fun onDrop(
                                                                        event: DragAndDropEvent
                                                                ): Boolean {
                                                                    isDraggingOver = false
                                                                    val dragEvent =
                                                                            event.toAndroidDragEvent()
                                                                    val clipData =
                                                                            dragEvent.clipData
                                                                    if (clipData != null &&
                                                                                    clipData.itemCount >
                                                                                            0
                                                                    ) {
                                                                        val sources =
                                                                                (0 until
                                                                                                clipData.itemCount)
                                                                                        .map {
                                                                                            clipData.getItemAt(
                                                                                                            it
                                                                                                    )
                                                                                                    .text
                                                                                                    .toString()
                                                                                                    .toPath()
                                                                                        }
                                                                        currentOnMove(sources)
                                                                        return true
                                                                    }
                                                                    return false
                                                                }

                                                                override fun onEntered(
                                                                        event: DragAndDropEvent
                                                                ) {
                                                                    isDraggingOver = true
                                                                }

                                                                override fun onExited(
                                                                        event: DragAndDropEvent
                                                                ) {
                                                                    isDraggingOver = false
                                                                }

                                                                override fun onEnded(
                                                                        event: DragAndDropEvent
                                                                ) {
                                                                    isDraggingOver = false
                                                                }
                                                            }
                                                        }
                                        )
                                    } else Modifier
                            )
                            .combinedClickable(onClick = onClick, onLongClick = onToggleSelection)
    ) {
        ListItem(
                headlineContent = {
                    if (isEditing) {
                        TextField(
                                value = editedName,
                                onValueChange = { editedName = it },
                                modifier = Modifier.focusRequester(focusRequester).fillMaxWidth(),
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
                },
                leadingContent = {
                    Icon(
                            if (file.isDirectory(fileSystem))
                                    painterResource(R.drawable.folder_24px)
                            else painterResource(R.drawable.docs_24px),
                            contentDescription = null,
                            tint =
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                    )
                },
                supportingContent = {
                    if (!file.isDirectory(fileSystem)) {
                        file.size(fileSystem)?.let { size -> Text(byteSizeString(size)) }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

fun byteSizeString(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var unitIdx = 0
    var bytesS = bytes.toDouble()
    while (bytesS >= 1024) {
        bytesS /= 1024
        unitIdx++
    }
    bytesS = (bytesS * 100).roundToLong() / 100.0
    return "$bytesS ${units[unitIdx]}"
}

private fun saveUriToPath(context: Context, uri: Uri, targetDir: Path) {
    val name =
            getFileName(context.contentResolver, uri)
                    ?: "shared_file_${System.currentTimeMillis()}"
    val targetPath = targetDir.resolve(name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        fs.write(targetPath) { writeAll(input.source()) }
    }
}

private fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
    if (uri.scheme == "content") {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    return cursor.getString(nameIndex)
                }
            }
        }
    }
    return uri.path?.substringAfterLast('/')
}
