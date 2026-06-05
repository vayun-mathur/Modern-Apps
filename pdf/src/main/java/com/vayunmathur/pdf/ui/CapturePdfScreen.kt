package com.vayunmathur.pdf.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.ui.components.CameraPreview
import com.vayunmathur.pdf.ui.components.SubcroppedImage
import com.vayunmathur.pdf.util.PdfViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturePdfScreen(
    viewModel: PdfViewModel,
    onBack: () -> Unit,
    onPdfCreated: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val images by viewModel.capturedImages.collectAsState()
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var isCropping by rememberSaveable { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val newIndex = viewModel.addCapturedImage(it)
            selectedIndex = newIndex
            isCropping = true
        }
    }

    val pendingTargetUri = remember { mutableStateOf<Uri?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { targetUri ->
            pendingTargetUri.value = targetUri
            viewModel.exportCapturedPdf(targetUri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.pdfWriteResults.collect { result ->
            val pending = pendingTargetUri.value
            if (pending != null && result.targetUri == pending) {
                pendingTargetUri.value = null
                if (result.success) {
                    Toast.makeText(context, "PDF saved successfully", Toast.LENGTH_SHORT).show()
                    onPdfCreated(result.targetUri)
                } else {
                    Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Reset the in-memory captured-images list when leaving this screen so the
    // VM doesn't retain it for the entire process lifetime.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearCapturedImages() }
    }

    BackHandler {
        if (isCropping) {
            isCropping = false
        } else if (selectedIndex != null) {
            selectedIndex = null
        } else {
            onBack()
        }
    }

    val currentSelected = selectedIndex
    if (isCropping && currentSelected != null && currentSelected in images.indices) {
        val currentImage = images[currentSelected]
        CropScreen(
            uri = currentImage.uri,
            initialQuadrilateral = currentImage.quadrilateral,
            onCropDone = { newQuadrilateral ->
                viewModel.updateQuadrilateral(currentSelected, newQuadrilateral)
                isCropping = false
            },
            onBack = { isCropping = false }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.capture_pdf_title)) },
                    navigationIcon = {
                        IconNavigation(onBack)
                    },
                    actions = {
                        val sel = selectedIndex
                        if (sel != null) {
                            IconButton(onClick = { isCropping = true }) {
                                IconCrop()
                            }
                            IconButton(onClick = {
                                viewModel.removeCapturedImage(sel)
                                selectedIndex = null
                            }) {
                                IconDelete()
                            }
                        } else {
                            IconButton(onClick = { galleryLauncher.launch("image/*") }) {
                                IconUpload()
                            }
                            if (images.isNotEmpty()) {
                                IconButton(onClick = {
                                    createDocumentLauncher.launch("captured_${System.currentTimeMillis()}.pdf")
                                }) {
                                    IconSave()
                                }
                            }
                        }
                    }
                )
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
                                state = state,
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
                                        .clickable { selectedIndex = index },
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
                                    .clickable { selectedIndex = null },
                                contentAlignment = Alignment.Center
                            ) {
                                IconAdd()
                            }
                        }
                    }
                }
            }
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
                                selectedIndex = newIndex
                                isCropping = true
                            }
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
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
