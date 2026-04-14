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
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconRotateLeft
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.SyncWorker
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

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var transformedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(photo?.uri) {
        val uri = photo?.uri?.toUri() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(inputStream, null, options)

                    var inSampleSize = 1
                    val targetW = 2048
                    val targetH = 2048
                    if (options.outHeight > targetH || options.outWidth > targetW) {
                        val halfHeight = options.outHeight / 2
                        val halfWidth = options.outWidth / 2
                        while (halfHeight / inSampleSize >= targetH && halfWidth / inSampleSize >= targetW) {
                            inSampleSize *= 2
                        }
                    }

                    options.inJustDecodeBounds = false
                    options.inSampleSize = inSampleSize

                    context.contentResolver.openInputStream(uri)?.use { inputStream2 ->
                        originalBitmap = BitmapFactory.decodeStream(inputStream2, null, options)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(originalBitmap, rotation, isCropping) {
        val original = originalBitmap ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val matrix = Matrix()
            matrix.postRotate(rotation)

            var result = Bitmap.createBitmap(
                original,
                0, 0, original.width, original.height,
                matrix, true
            )

            if (!isCropping) {
                val left = (cropRect.left * result.width).roundToInt().coerceIn(0, result.width - 1)
                val top = (cropRect.top * result.height).roundToInt().coerceIn(0, result.height - 1)
                val width = ((cropRect.right - cropRect.left) * result.width).roundToInt().coerceAtMost(result.width - left)
                val height = ((cropRect.bottom - cropRect.top) * result.height).roundToInt().coerceAtMost(result.height - top)

                if (width > 0 && height > 0) {
                    result = Bitmap.createBitmap(result, left, top, width, height)
                }
            }
            transformedBitmap = result
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_edit_photo)) },
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
                                    text = { Text(stringResource(R.string.action_save)) },
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
                                    text = { Text(stringResource(R.string.action_save_as_copy)) },
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
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                val maxWidth = constraints.maxWidth.toFloat()
                val maxHeight = constraints.maxHeight.toFloat()

                photo?.let { p ->
                    val isFlipped = (rotation / 90f).roundToInt() % 2 != 0
                    val photoRatio = if (isFlipped) p.height.toFloat() / p.width.toFloat() else p.width.toFloat() / p.height.toFloat()
                    
                    val displayRatio = if (isCropping) photoRatio else (cropRect.width / cropRect.height) * photoRatio
                    val containerRatio = maxWidth / maxHeight

                    val (viewportWidth, viewportHeight) = if (displayRatio > containerRatio) {
                        maxWidth to (maxWidth / displayRatio)
                    } else {
                        (maxHeight * displayRatio) to maxHeight
                    }

                    val density = LocalDensity.current
                    val viewportWidthDp = with(density) { viewportWidth.toDp() }
                    val viewportHeightDp = with(density) { viewportHeight.toDp() }

                    Box(
                        modifier = Modifier
                            .size(viewportWidthDp, viewportHeightDp)
                            .graphicsLayer { clip = false },
                        contentAlignment = Alignment.Center
                    ) {
                        transformedBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

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

            val path = Path().apply {
                addRect(Rect(0f, 0f, width, height))
                addRect(rect)
                fillType = PathFillType.EvenOdd
            }
            drawPath(path, Color.Black.copy(alpha = 0.5f))

            drawRect(
                color = Color.White,
                topLeft = Offset(rect.left, rect.top),
                size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Body drag handle
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (cropRect.left * width).roundToInt(),
                        (cropRect.top * height).roundToInt()
                    )
                }
                .size(
                    width = with(LocalDensity.current) { (cropRect.width * width).toDp() },
                    height = with(LocalDensity.current) { (cropRect.height * height).toDp() }
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dx = dragAmount.x / width
                        val dy = dragAmount.y / height

                        val newLeft = (cropRect.left + dx).coerceIn(0f, 1f - cropRect.width)
                        val newTop = (cropRect.top + dy).coerceIn(0f, 1f - cropRect.height)

                        onCropRectChange(
                            Rect(
                                left = newLeft,
                                top = newTop,
                                right = newLeft + cropRect.width,
                                bottom = newTop + cropRect.height
                            )
                        )
                    }
                }
        )

        // Corners
        Handle(
            offset = Offset(cropRect.left * width, cropRect.top * height),
            onDrag = { delta ->
                val newLeft = (cropRect.left + delta.x / width).coerceIn(0f, cropRect.right - 0.05f)
                val newTop = (cropRect.top + delta.y / height).coerceIn(0f, cropRect.bottom - 0.05f)
                onCropRectChange(cropRect.copy(left = newLeft, top = newTop))
            }
        )
        Handle(
            offset = Offset(cropRect.right * width, cropRect.top * height),
            onDrag = { delta ->
                val newRight = (cropRect.right + delta.x / width).coerceIn(cropRect.left + 0.05f, 1f)
                val newTop = (cropRect.top + delta.y / height).coerceIn(0f, cropRect.bottom - 0.05f)
                onCropRectChange(cropRect.copy(right = newRight, top = newTop))
            }
        )
        Handle(
            offset = Offset(cropRect.left * width, cropRect.bottom * height),
            onDrag = { delta ->
                val newLeft = (cropRect.left + delta.x / width).coerceIn(0f, cropRect.right - 0.05f)
                val newBottom = (cropRect.bottom + delta.y / height).coerceIn(cropRect.top + 0.05f, 1f)
                onCropRectChange(cropRect.copy(left = newLeft, bottom = newBottom))
            }
        )
        Handle(
            offset = Offset(cropRect.right * width, cropRect.bottom * height),
            onDrag = { delta ->
                val newRight = (cropRect.right + delta.x / width).coerceIn(cropRect.left + 0.05f, 1f)
                val newBottom = (cropRect.bottom + delta.y / height).coerceIn(cropRect.top + 0.05f, 1f)
                onCropRectChange(cropRect.copy(right = newRight, bottom = newBottom))
            }
        )

        // Side handles
        Handle(
            offset = Offset((cropRect.left + cropRect.right) / 2 * width, cropRect.top * height),
            onDrag = { delta ->
                val newTop = (cropRect.top + delta.y / height).coerceIn(0f, cropRect.bottom - 0.05f)
                onCropRectChange(cropRect.copy(top = newTop))
            }
        )
        Handle(
            offset = Offset((cropRect.left + cropRect.right) / 2 * width, cropRect.bottom * height),
            onDrag = { delta ->
                val newBottom = (cropRect.bottom + delta.y / height).coerceIn(cropRect.top + 0.05f, 1f)
                onCropRectChange(cropRect.copy(bottom = newBottom))
            }
        )
        Handle(
            offset = Offset(cropRect.left * width, (cropRect.top + cropRect.bottom) / 2 * height),
            onDrag = { delta ->
                val newLeft = (cropRect.left + delta.x / width).coerceIn(0f, cropRect.right - 0.05f)
                onCropRectChange(cropRect.copy(left = newLeft))
            }
        )
        Handle(
            offset = Offset(cropRect.right * width, (cropRect.top + cropRect.bottom) / 2 * height),
            onDrag = { delta ->
                val newRight = (cropRect.right + delta.x / width).coerceIn(cropRect.left + 0.05f, 1f)
                onCropRectChange(cropRect.copy(right = newRight))
            }
        )
    }
}

@Composable
fun Handle(offset: Offset, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSize = 24.dp
    val handleRadiusPx = with(density) { (handleSize / 2).toPx() }
    val currentOnDrag by rememberUpdatedState(onDrag)

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
            .border(1.dp, Color.Black, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount)
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
    val nowSeconds = System.currentTimeMillis() / 1000
    
    if (asCopy) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_${photo.name}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
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
            val updateValues = ContentValues().apply {
                put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
            }
            resolver.update(uri, updateValues, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
