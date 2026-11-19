package com.vayunmathur.pdf.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.BottomAppBar
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberPermissionRequest
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.ui.components.CameraPreview
import com.vayunmathur.pdf.ui.components.SubcroppedImage
import com.vayunmathur.pdf.platform.PdfViewModel
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.longPressDraggableHandle
import com.vayunmathur.library.ui.appBarScrollBehavior

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturePdfScreen(
    viewModel: PdfViewModel,
    selectedIndex: Int?,
    onBack: () -> Unit,
    onPdfCreated: (Uri) -> Unit,
    onSelectPage: (Int?) -> Unit,
    onOpenCrop: (Int) -> Unit,
) {
    val context = LocalContext.current
    val pdfSavedMsg = stringResource(R.string.pdf_saved)
    val pdfSaveFailedMsg = stringResource(R.string.failed_to_save_pdf)
    val images by viewModel.capturedImages.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionRequest = rememberPermissionRequest(
        Manifest.permission.CAMERA
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val newIndex = viewModel.addCapturedImage(it)
            onSelectPage(newIndex)
            onOpenCrop(newIndex)
        }
    }

    val pendingTargetUri = remember { mutableStateOf<Uri?>(null) }
    var scanFilter by remember { mutableStateOf(com.vayunmathur.pdf.platform.ScanFilter.NONE) }
    var addOcr by remember { mutableStateOf(false) }
    var showScanOptions by remember { mutableStateOf(false) }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { targetUri ->
            pendingTargetUri.value = targetUri
            viewModel.exportCapturedPdf(targetUri, scanFilter, addOcr)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.pdfWriteResults.collect { result ->
            val pending = pendingTargetUri.value
            if (pending != null && result.targetUri == pending) {
                pendingTargetUri.value = null
                if (result.success) {
                    AppMessages.show(pdfSavedMsg)
                    onPdfCreated(result.targetUri)
                } else {
                    AppMessages.show(pdfSaveFailedMsg)
                }
            }
        }
    }

    run {
        AppScaffold(
            title = stringResource(R.string.capture_pdf_title),
            onNavigateBack = onBack,
            actions = {
                    val sel = selectedIndex
                    if (sel != null) {
                        IconButton(onClick = { onOpenCrop(sel) }) {
                            IconCrop()
                        }
                        IconButton(onClick = {
                            viewModel.removeCapturedImage(sel)
                            onSelectPage(null)
                        }) {
                            IconDelete()
                        }
                    } else {
                        IconButton(onClick = { galleryLauncher.launch("image/*") }) {
                            IconUpload()
                        }
                        if (images.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { showScanOptions = true }) {
                                    IconMoreVert()
                                }
                                com.vayunmathur.library.ui.DropdownMenu(
                                    expanded = showScanOptions,
                                    onDismissRequest = { showScanOptions = false },
                                ) {
                                    for (f in com.vayunmathur.pdf.platform.ScanFilter.entries) {
                                        com.vayunmathur.library.ui.DropdownMenuItem(
                                            leadingIcon = {
                                                if (scanFilter == f) com.vayunmathur.library.ui.IconCheck()
                                            },
                                            text = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                            onClick = { scanFilter = f },
                                        )
                                    }
                                    com.vayunmathur.library.ui.HorizontalDivider()
                                    com.vayunmathur.library.ui.DropdownMenuItem(
                                        leadingIcon = {
                                            if (addOcr) com.vayunmathur.library.ui.IconCheck()
                                        },
                                        text = { Text(stringResource(R.string.searchable_ocr)) },
                                        onClick = { addOcr = !addOcr },
                                    )
                                }
                            }
                            IconButton(onClick = {
                                createDocumentLauncher.launch("captured_${System.currentTimeMillis()}.pdf")
                            }) {
                                IconSave()
                            }
                        }
                    }
            },
            bottomBar = {
                BottomAppBar(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    modifier = Modifier.height(100.dp)
                ) {
                    val lazyListState = rememberLazyListState()
                    val state = rememberReorderableLazyListState(lazyListState) { from, to ->
                        if (to.index >= images.size || from.index >= images.size) return@rememberReorderableLazyListState
                        viewModel.moveCapturedImage(from.index, to.index)
                    }
                    LazyRow(
                        state = lazyListState,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(images, key = { _, img -> img.uri.toString() }) { index, img ->
                            ReorderableItem(
                                reorderState = state,
                                key = img.uri.toString()
                            ) { isDragging ->
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            width = 2.dp,
                                            color = if (selectedIndex == index) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { onSelectPage(index) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    SubcroppedImage(
                                        image = img
                                    )
                                }
                            }
                        }
                        item {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onSelectPage(null) },
                                contentAlignment = Alignment.Center
                            ) {
                                IconAdd()
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                val sel = selectedIndex
                if (sel != null && sel in images.indices) {
                    val currentImage = images[sel]
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        SubcroppedImage(
                            image = currentImage
                        )
                    }
                } else {
                    if (hasCameraPermission) {
                        CameraPreview(
                            onImageCaptured = { uri ->
                                val newIndex = viewModel.addCapturedImage(uri)
                                onSelectPage(newIndex)
                                onOpenCrop(newIndex)
                            }
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(onClick = cameraPermissionRequest) {
                                Text(stringResource(R.string.request_camera_permission))
                            }
                            Button(onClick = { galleryLauncher.launch("image/*") }, Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.upload_image))
                            }
                        }
                    }
                }
            }
        }
    }
}
