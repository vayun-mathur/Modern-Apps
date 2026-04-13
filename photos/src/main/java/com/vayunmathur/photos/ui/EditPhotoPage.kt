package com.vayunmathur.photos.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconRotateLeft
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPhotoPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, id: Long) {
    val context = LocalContext.current
    val photo by viewModel.getNullable<Photo>(id)
    val scope = rememberCoroutineScope()

    var isCropping by remember { mutableStateOf(false) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var cropRect by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var showSaveMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Photo") },
                navigationIcon = {
                    IconNavigation(backStack)
                },
                actions = {
                    if (isCropping) {
                        IconButton(onClick = { isCropping = false }) {
                            IconCheck()
                        }
                        IconButton(onClick = {
                            cropRect = Rect(0f, 0f, 1f, 1f)
                            isCropping = false
                        }) {
                            IconClose()
                        }
                    } else {
                        IconButton(onClick = { isCropping = true }) {
                            IconCrop()
                        }
                        IconButton(onClick = { rotation -= 90f }) {
                            IconRotateLeft()
                        }
                        IconButton(onClick = { rotation += 90f }) {
                            IconRotateRight()
                        }
                        Box {
                            IconButton(onClick = { showSaveMenu = true }) {
                                IconSave()
                            }
                            DropdownMenu(
                                expanded = showSaveMenu,
                                onDismissRequest = { showSaveMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Save") },
                                    onClick = {
                                        showSaveMenu = false
                                        photo?.let {
                                            scope.launch {
                                                savePhoto(context, viewModel, it, rotation, cropRect, false)
                                                backStack.pop()
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save as Copy") },
                                    onClick = {
                                        showSaveMenu = false
                                        photo?.let {
                                            scope.launch {
                                                savePhoto(context, viewModel, it, rotation, cropRect, true)
                                                backStack.pop()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val maxWidth = constraints.maxWidth.toFloat()
                val maxHeight = constraints.maxHeight.toFloat()

                photo?.let { p ->
                    val isFlipped = (rotation / 90f).roundToInt() % 2 != 0
                    val photoRatio = if (isFlipped) p.height.toFloat() / p.width.toFloat() else p.width.toFloat() / p.height.toFloat()
                    val containerRatio = maxWidth / maxHeight

                    val (imageWidth, imageHeight) = if (photoRatio > containerRatio) {
                        maxWidth to (maxWidth / photoRatio)
                    } else {
                        (maxHeight * photoRatio) to maxHeight
                    }

                    Box(modifier = Modifier.size(imageWidth.dp, imageHeight.dp)) {
                        AsyncImage(
                            model = p.uri.toUri(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    rotationZ = rotation
                                },
                            contentScale = ContentScale.Fit
                        )

                        if (isCropping) {
                            CropOverlay(
                                cropRect = cropRect,
                                onCropRectChange = { cropRect = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CropOverlay(
    cropRect: Rect,
    onCropRectChange: (Rect) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val rect = Rect(
                left = cropRect.left * width,
                top = cropRect.top * height,
                right = cropRect.right * width,
                bottom = cropRect.bottom * height
            )
            
            drawRect(
                color = Color.White,
                topLeft = Offset(rect.left, rect.top),
                size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Handle(
            offset = Offset(cropRect.left * width, cropRect.top * height),
            onDrag = { delta ->
                val newLeft = (cropRect.left + delta.x / width).coerceIn(0f, cropRect.right - 0.1f)
                val newTop = (cropRect.top + delta.y / height).coerceIn(0f, cropRect.bottom - 0.1f)
                onCropRectChange(cropRect.copy(left = newLeft, top = newTop))
            }
        )
        Handle(
            offset = Offset(cropRect.right * width, cropRect.top * height),
            onDrag = { delta ->
                val newRight = (cropRect.right + delta.x / width).coerceIn(cropRect.left + 0.1f, 1f)
                val newTop = (cropRect.top + delta.y / height).coerceIn(0f, cropRect.bottom - 0.1f)
                onCropRectChange(cropRect.copy(right = newRight, top = newTop))
            }
        )
        Handle(
            offset = Offset(cropRect.left * width, cropRect.bottom * height),
            onDrag = { delta ->
                val newLeft = (cropRect.left + delta.x / width).coerceIn(0f, cropRect.right - 0.1f)
                val newBottom = (cropRect.bottom + delta.y / height).coerceIn(cropRect.top + 0.1f, 1f)
                onCropRectChange(cropRect.copy(left = newLeft, bottom = newBottom))
            }
        )
        Handle(
            offset = Offset(cropRect.right * width, cropRect.bottom * height),
            onDrag = { delta ->
                val newRight = (cropRect.right + delta.x / width).coerceIn(cropRect.left + 0.1f, 1f)
                val newBottom = (cropRect.bottom + delta.y / height).coerceIn(cropRect.top + 0.1f, 1f)
                onCropRectChange(cropRect.copy(right = newRight, bottom = newBottom))
            }
        )
    }
}

@Composable
fun Handle(offset: Offset, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSize = 30.dp
    val handleRadiusPx = with(density) { (handleSize / 2).toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (offset.x - handleRadiusPx).roundToInt(),
                    (offset.y - handleRadiusPx).roundToInt()
                )
            }
            .size(handleSize)
            .background(Color.White, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
    )
}

suspend fun savePhoto(
    context: android.content.Context,
    viewModel: DatabaseViewModel,
    photo: Photo,
    rotation: Float,
    cropRect: Rect,
    asCopy: Boolean
) = withContext(Dispatchers.IO) {
    val inputStream: InputStream? = context.contentResolver.openInputStream(photo.uri.toUri())
    val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return@withContext
    
    val matrix = Matrix()
    matrix.postRotate(rotation)
    
    var transformedBitmap = Bitmap.createBitmap(
        originalBitmap,
        0, 0, originalBitmap.width, originalBitmap.height,
        matrix, true
    )
    
    val left = (cropRect.left * transformedBitmap.width).roundToInt().coerceIn(0, transformedBitmap.width - 1)
    val top = (cropRect.top * transformedBitmap.height).roundToInt().coerceIn(0, transformedBitmap.height - 1)
    val width = ((cropRect.right - cropRect.left) * transformedBitmap.width).roundToInt().coerceAtMost(transformedBitmap.width - left)
    val height = ((cropRect.bottom - cropRect.top) * transformedBitmap.height).roundToInt().coerceAtMost(transformedBitmap.height - top)
    
    if (width > 0 && height > 0) {
        transformedBitmap = Bitmap.createBitmap(transformedBitmap, left, top, width, height)
    }

    val resolver = context.contentResolver
    
    if (asCopy) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_${photo.name}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { out ->
                transformedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }
    } else {
        // Overwrite
        val uri = photo.uri.toUri()
        try {
            resolver.openOutputStream(uri, "rwt")?.use { out ->
                transformedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    syncPhotos(context, viewModel)
}
