package com.vayunmathur.pdf.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExtendedFloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.data.Quadrilateral
import com.vayunmathur.pdf.platform.AutoFrameDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    uri: Uri,
    initialQuadrilateral: Quadrilateral?,
    onCropDone: (Quadrilateral) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var quadrilateral by remember {
        mutableStateOf(initialQuadrilateral ?: Quadrilateral.default())
    }
    var originalSize by remember { mutableStateOf<IntSize?>(null) }
    var decodedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } catch (_: Exception) { null }
        }
        if (bitmap != null) {
            originalSize = IntSize(bitmap.width, bitmap.height)
            decodedBitmap = bitmap
            if (initialQuadrilateral == null) {
                withContext(Dispatchers.Default) {
                    AutoFrameDetector.detect(bitmap)
                }?.let { quadrilateral = it }
            }
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    // Zero dimensions mean the bounds decode failed (null stream or
                    // undecodable data); leave originalSize null so the screen keeps
                    // showing its loading state instead of dividing by zero below.
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        originalSize = IntSize(options.outWidth, options.outHeight)
                    }
                }
            }
        }
    }

    AppScaffold(
        title = stringResource(R.string.crop_image),
        onNavigateBack = onBack,
        actions = {
            Button(
                onClick = {
                    decodedBitmap?.let { bmp ->
                        scope.launch {
                            withContext(Dispatchers.Default) {
                                AutoFrameDetector.detect(bmp)
                            }?.let { quadrilateral = it }
                        }
                    }
                },
                enabled = decodedBitmap != null
            ) {
                Text(stringResource(R.string.auto_detect))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = 32.dp, bottom = 64.dp, start = 48.dp, end = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxWidth = constraints.maxWidth.toFloat()
            val maxHeight = constraints.maxHeight.toFloat()

            originalSize?.let { size ->
                val photoRatio = size.width.toFloat() / size.height.toFloat()
                val containerRatio = maxWidth / maxHeight
                // Calculate size to fit within container while maintaining aspect ratio
                // Use maximum available space
                val (viewportWidth, viewportHeight) = if (photoRatio > containerRatio) {
                    // Photo is wider than container - fit to width
                    maxWidth to (maxWidth / photoRatio)
                } else {
                    // Photo is taller than container - fit to height
                    (maxHeight * photoRatio) to maxHeight
                }

                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Single box with exact size containing both image and overlay
                    // This ensures they are perfectly aligned
                    Box(
                        Modifier.size(
                            with(LocalDensity.current) { viewportWidth.toDp() },
                            with(LocalDensity.current) { viewportHeight.toDp() }
                        )
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        CropOverlay(
                            quadrilateral = quadrilateral,
                            onQuadrilateralChange = { quadrilateral = it },
                            bitmap = decodedBitmap
                        )
                    }
                }
            } ?: AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        ExtendedFloatingActionButton(
            onClick = { onCropDone(quadrilateral) },
            text = { Text(stringResource(R.string.continue_label)) },
            icon = { IconSave() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
        }
    }
}
