package com.vayunmathur.translate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.translate.R
import com.vayunmathur.translate.platform.TranslateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest edge (px) the picked image is downscaled to before OCR and display. */
private const val IMAGE_MAX_DIMEN = 1600

/**
 * Fullscreen translation of a gallery image, mirroring the camera screen: the image fills
 * a black stage and the same [CameraOverlay] plates + language bar sit on top of it.
 *
 * The bitmap is fitted (not cropped) so no edge text is lost; the overlay is wrapped in a
 * box sized to the fitted image rect, so its stretch mapping lands exactly like the
 * camera's viewport-cropped mapping does.
 */
// Broad catch is deliberate: decoding user-supplied content throws undocumented
// RuntimeExceptions, which read as "couldn't read that image" rather than a crash.
@Suppress("TooGenericExceptionCaught")
@Composable
fun ImageTranslateScreen(
    viewModel: TranslateViewModel,
    imageUri: String,
    onBack: () -> Unit,
    onPickAnother: () -> Unit,
    onOpenLanguagePicker: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    // Fresh holder per image: the dispose below frees exactly the decode it belongs
    // to. Keying that effect on the bitmap itself recycled each new decode on arrival
    // and stuck the screen on the spinner forever.
    val bitmapState = remember(imageUri) { mutableStateOf<Bitmap?>(null) }
    var bitmap by bitmapState
    var failed by remember(imageUri) { mutableStateOf(false) }
    val overlays = remember(imageUri) { mutableStateOf<List<OverlayBox>>(emptyList()) }
    val translations = remember(imageUri) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var viewSize by remember(imageUri) { mutableStateOf(IntSize.Zero) }

    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val translationAvailable by viewModel.translationAvailable.collectAsState()

    DisposableEffect(imageUri) {
        onDispose {
            // The screen owns its decoded copy; the uri grant outlives it.
            bitmapState.value?.takeIf { !it.isRecycled }?.recycle()
            bitmapState.value = null
        }
    }

    // Decode once per image; OCR follows as its own step so the image shows
    // immediately even while the recognizer is still loading its models.
    LaunchedEffect(imageUri) {
        try {
            bitmap = withContext(Dispatchers.Default) {
                decodePickedImage(context, Uri.parse(imageUri))
            } ?: run {
                failed = true
                return@LaunchedEffect
            }
        } catch (e: Exception) {
            Log.error(TAG, "Image decode failed", e)
            failed = true
        }
    }

    // Boxes come back in bitmap coords, which is exactly the space the overlay
    // maps from.
    LaunchedEffect(bitmap) {
        val bmp = bitmap ?: return@LaunchedEffect
        try {
            val result = viewModel.ocr.recognizeDetailed(bmp)
            overlays.value = result.boxes.map { box ->
                OverlayBox(box.corners.map { Offset(it.x, it.y) }, box.text)
            }
        } catch (e: Exception) {
            Log.error(TAG, "Image OCR failed", e)
        }
    }

    // One background translator, same memo behaviour as the camera loop.
    TranslationLoop(
        viewModel = viewModel,
        sourceLang = sourceLang,
        targetLang = targetLang,
        translationAvailable = translationAvailable,
        overlays = overlays.value,
        onTranslations = { translations.value = it },
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val bmp = bitmap
        when {
            bmp != null && !bmp.isRecycled -> {
                val density = LocalDensity.current
                // Fit math in px: the overlay box below is exactly this rect, so the
                // plate mapping is a plain stretch with no letterbox arithmetic.
                val vw = viewSize.width.toFloat()
                val vh = viewSize.height.toFloat()
                val scale = if (vw > 0 && vh > 0) {
                    minOf(vw / bmp.width, vh / bmp.height)
                } else {
                    0f
                }
                val dispWpx = (bmp.width * scale).toInt()
                val dispHpx = (bmp.height * scale).toInt()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewSize = it },
                    contentAlignment = Alignment.Center,
                ) {
                    if (dispWpx > 0 && dispHpx > 0) {
                        Box(
                            modifier = Modifier.size(
                                width = with(density) { dispWpx.toDp() },
                                height = with(density) { dispHpx.toDp() },
                            ),
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                // Already sized to the fitted rect, so no second
                                // scale is left to disagree with the overlay.
                                contentScale = ContentScale.FillBounds,
                            )
                            CameraOverlay(
                                overlays = overlays.value,
                                translations = translations.value,
                                translationAvailable = translationAvailable,
                                frameW = bmp.width,
                                frameH = bmp.height,
                                viewW = dispWpx,
                                viewH = dispHpx,
                                sourceLang = sourceLang,
                                targetLang = targetLang,
                                paused = false,
                                onTogglePaused = {},
                                torchOn = false,
                                onToggleTorch = {},
                                onBack = onBack,
                                onOpenLanguagePicker = onOpenLanguagePicker,
                                showCaptureControls = false,
                            )
                        }
                    }
                }
            }
            failed -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.could_not_read_image),
                        color = Color.White,
                    )
                    TextButton(onClick = onPickAnother) {
                        Text(
                            text = stringResource(R.string.pick_another_image),
                            color = Color.White,
                        )
                    }
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Decodes [uri] to an upright bitmap, downscaled so its longest edge is at most
 * [IMAGE_MAX_DIMEN]. Returns null when the content cannot be decoded. The caller owns
 * the returned bitmap.
 */
private fun decodePickedImage(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > IMAGE_MAX_DIMEN || bounds.outHeight / sample > IMAGE_MAX_DIMEN) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    var decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, opts)
    } ?: return null
    // EXIF orientation lives beside the pixels, so it needs its own stream.
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(270f)
                postScale(-1f, 1f)
            }
        }
    }
    val needsTransform = orientation != ExifInterface.ORIENTATION_NORMAL &&
        orientation != ExifInterface.ORIENTATION_UNDEFINED
    if (needsTransform) {
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        decoded = rotated
    }
    return decoded
}
