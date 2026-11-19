package com.vayunmathur.photos.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.VideoTool
import com.vayunmathur.photos.platform.VideoEditViewModel

@OptIn(UnstableApi::class)
@Composable
fun VideoEditPage(vm: VideoEditViewModel, id: Long, uri: String?) {
    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(id, uri) { vm.loadVideo(id, uri) }

    val photo by vm.photo.collectAsState()
    val state by vm.state.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val progress by vm.progress.collectAsState()

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.onWritePermissionGranted()
        else vm.onWritePermissionDenied()
    }
    val writePermissionRequest by vm.writePermissionRequest.collectAsState()
    LaunchedEffect(writePermissionRequest) {
        writePermissionRequest?.let {
            writePermissionLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    var selectedTool by remember { mutableStateOf(VideoTool.Trim) }
    var showSaveDialog by remember { mutableStateOf(false) }

    val currentPhoto = photo
    if (currentPhoto == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    AppScaffold(
        title = { Text(stringResource(R.string.title_edit_video), maxLines = 1) },
        onNavigateBack = { activity?.finish() },
        actions = {
            IconButton(onClick = { showSaveDialog = true }, enabled = !exporting) { IconSave() }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(padding),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                VideoEditPreview(
                    vm = vm,
                    state = state,
                    uri = currentPhoto.uri.toUri(),
                    showCropOverlay = selectedTool == VideoTool.CropRotate,
                )
            }

            ToolPanel(vm = vm, state = state, tool = selectedTool)

            ToolTabs(selected = selectedTool, onSelect = { selectedTool = it })
        }
    }

    if (showSaveDialog) {
        SaveDialog(
            onDismiss = { showSaveDialog = false },
            onSaveCopy = {
                showSaveDialog = false
                vm.export(context, currentPhoto, asCopy = true) { activity?.finish() }
            },
            onOverwrite = {
                showSaveDialog = false
                vm.export(context, currentPhoto, asCopy = false) { activity?.finish() }
            },
        )
    }

    if (exporting) {
        ExportProgressDialog(progress = progress, onCancel = { vm.cancelExport() })
    }
}
