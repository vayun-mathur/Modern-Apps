package com.vayunmathur.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.FileProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import java.io.File
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
        DirectoryPage(Environment.getExternalStorageDirectory())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPage(rootFile: File) {
    val context = LocalContext.current
    var currentDirectory by remember { mutableStateOf(rootFile) }
    var selectedPaths by remember(currentDirectory) { mutableStateOf(setOf<String>()) }
    var pathBeingRenamed by remember { mutableStateOf<String?>(null) }

    // Data state
    var filesList by remember(currentDirectory) {
        mutableStateOf(currentDirectory.listFiles()?.partition { it.isDirectory }
            ?: Pair(emptyList<File>(), emptyList<File>()))
    }

    fun forceRefresh() {
        filesList = currentDirectory.listFiles()?.partition { it.isDirectory }
            ?: Pair(emptyList<File>(), emptyList<File>())
    }
    val (directories, files) = filesList

    val focusManager = LocalFocusManager.current

    BackHandler(currentDirectory.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
        if (selectedPaths.isNotEmpty()) {
            selectedPaths = emptySet()
        } else {
            currentDirectory = currentDirectory.parentFile ?: currentDirectory
        }
    }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                focusManager.clearFocus()
                pathBeingRenamed = null
            },
        topBar = {
            TopAppBar(
                title = { Text(currentDirectory.name.ifEmpty { "Internal Storage" }) },
                actions = {
                    // Show Rename Button if exactly 1 is selected
                    if (selectedPaths.size == 1) {
                        IconButton(onClick = { pathBeingRenamed = selectedPaths.first() }) {
                            IconEdit()
                        }
                    }
                    // Delete Button
                    if (selectedPaths.isNotEmpty()) {
                        IconButton(onClick = {
                            selectedPaths.forEach { File(it).deleteRecursively() }
                            selectedPaths = emptySet()
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

            items(allItems, key = { it.absolutePath }) { child ->
                val isSelected = selectedPaths.contains(child.absolutePath)
                val isEditing = pathBeingRenamed == child.absolutePath

                DirectoryItem(
                    file = child,
                    isEditing = isEditing,
                    isSelected = isSelected,
                    onRename = { newName ->
                        val newFile = File(child.parentFile, newName)
                        if (child.renameTo(newFile)) {
                            forceRefresh()
                        }
                        pathBeingRenamed = null
                        selectedPaths = emptySet()
                    },
                    onToggleSelection = {
                        if(pathBeingRenamed != null) pathBeingRenamed = null
                        selectedPaths = if (isSelected) selectedPaths - child.absolutePath
                        else selectedPaths + child.absolutePath
                    },
                    onClick = {
                        if (selectedPaths.isNotEmpty()) {
                            selectedPaths = if (isSelected) selectedPaths - child.absolutePath
                            else selectedPaths + child.absolutePath
                        } else if (child.isDirectory) {
                            currentDirectory = child
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", child)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or  Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            }
                            context.startActivity(intent)
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryItem(
    file: File,
    isEditing: Boolean,
    isSelected: Boolean,
    onRename: (String) -> Unit,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit
) {
    var editedName by remember(isEditing) { mutableStateOf(file.name) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    ListItem(
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onToggleSelection
            ),
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
                Text(byteSizeString(file.length()))
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
        )
    )
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