package com.vayunmathur.photos.util

import android.app.Application
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import com.vayunmathur.library.log.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// --- ViewModel ------------------------------------------------------------------

enum class WallpaperLoadState { Idle, Loading, Loaded, Failed }

class WallpaperViewModel(application: Application) : AndroidViewModel(application) {

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    private val _loadState = MutableStateFlow(WallpaperLoadState.Idle)
    val loadState: StateFlow<WallpaperLoadState> = _loadState.asStateFlow()

    fun load(uriString: String) {
        if (_loadState.value == WallpaperLoadState.Loading) return
        _loadState.value = WallpaperLoadState.Loading
        val ctx: Context = getApplication()
        viewModelScope.launch(Dispatchers.IO) {
            val prev = _bitmap.value
            try {
                val uri = uriString.toUri()
                val source = ImageDecoder.createSource(ctx.contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    // Cap at 4096 on the longest edge to avoid OOM while keeping quality
                    val maxEdge = 4096
                    if (w > maxEdge || h > maxEdge) {
                        val scale = maxEdge.toFloat() / max(w, h)
                        decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                else bmp.copy(Bitmap.Config.ARGB_8888, true).also {
                    if (bmp !== it) runCatching { bmp.recycle() }
                }
                prev?.recycle()
                _bitmap.value = argb
                _loadState.value = WallpaperLoadState.Loaded
            } catch (e: IOException) {
                Log.error(TAG, "wallpaper decode failed for $uriString", e)
                prev?.let { _bitmap.value = it }
                _loadState.value = WallpaperLoadState.Failed
            } catch (e: SecurityException) {
                Log.error(TAG, "wallpaper decode failed for $uriString", e)
                prev?.let { _bitmap.value = it }
                _loadState.value = WallpaperLoadState.Failed
            }
        }
    }

    override fun onCleared() {
        _bitmap.value?.recycle()
        _bitmap.value = null
    }

    companion object {
        private const val TAG = "WallpaperViewModel"
    }
}

@Suppress("FunctionName")
fun WallpaperViewModelFactory(application: Application): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { WallpaperViewModel(application) }
    }

// --- Wallpaper application utility ----------------------------------------------

object WallpaperUtil {

    const val TAG = "WallpaperUtil"

    private fun hasValidCropInputs(srcW: Int, srcH: Int, baseDisplayW: Float, baseDisplayH: Float): Boolean {
        val hasSource = srcW > 0 && srcH > 0
        val hasDisplay = baseDisplayW > 0f && baseDisplayH > 0f
        return hasSource && hasDisplay
    }

    /**
     * Result of the set-wallpaper operation.
     */
    sealed class SetResult {
        data object Success : SetResult()
        data class Failure(val exception: Exception) : SetResult()
    }

    /**
     * Set [source] as wallpaper.
     *
     * @param context          Application or activity context.
     * @param source           Full-resolution source bitmap (not recycled by this call).
     * @param viewport         Pre-computed crop in source-pixel coordinates.
     * @param targetWidth      Final output width (screen or desiredMinimumWidth).
     * @param targetHeight     Final output height (screen or desiredMinimumHeight).
     * @param which            WallpaperManager.FLAG_SYSTEM / FLAG_LOCK / combined.
     * @param isScrollable     Whether to request a wider launcher hint.
     * @param screenW          Physical screen width in px (for suggestDesiredDimensions static path).
     * @param screenH          Physical screen height in px.
     */
    suspend fun setWallpaper(
        context: Context,
        source: Bitmap,
        viewport: Rect,
        targetWidth: Int,
        targetHeight: Int,
        which: Int,
        isScrollable: Boolean,
        screenW: Int,
        screenH: Int,
    ): SetResult = withContext(Dispatchers.IO) {
        try {
            val wm = WallpaperManager.getInstance(context)

            // Unified scrollable width: must match the target we scale to. On minSdk 31 the launcher
            // hint controls parallax: scrollableW = max(desiredMinimumWidth, 2*screenW) wider = parallax,
            // static = screenW. Using targetWidth directly avoids the earlier 1.5x/2x mismatch.
            if (isScrollable) {
                wm.suggestDesiredDimensions(targetWidth, targetHeight.coerceAtLeast(screenH))
            } else {
                wm.suggestDesiredDimensions(screenW, screenH)
            }

            // Crop → scale
            val cropped = runCatching {
                // Guard against empty / OOB rects
                val safeRect = Rect(
                    viewport.left.coerceIn(0, source.width - 1),
                    viewport.top.coerceIn(0, source.height - 1),
                    viewport.right.coerceIn(1, source.width),
                    viewport.bottom.coerceIn(1, source.height),
                )
                // Ensure non-empty
                if (safeRect.width() <= 0 || safeRect.height() <= 0) {
                    Rect(0, 0, source.width, source.height)
                } else safeRect
            }.getOrElse { Rect(0, 0, source.width, source.height) }

            val cropBmp = Bitmap.createBitmap(source, cropped.left, cropped.top, cropped.width(), cropped.height())
            val finalBmp = cropBmp.scale(targetWidth, targetHeight)
            if (cropBmp !== finalBmp) cropBmp.recycle()

            // PNG alpha → black background (wallpaper does not support alpha)
            val opaqueBmp = if (finalBmp.hasAlpha()) {
                val opaque = createBitmap(finalBmp.width, finalBmp.height)
                Canvas(opaque).apply {
                    drawColor(0xFF000000.toInt())
                    drawBitmap(finalBmp, 0f, 0f, null)
                }
                finalBmp.recycle()
                opaque
            } else finalBmp

            try {
                // minSdk 31 — FLAG_LOCK available, no legacy branch needed.
                wm.setBitmap(opaqueBmp, null, true, which)
                SetResult.Success
            } finally {
                opaqueBmp.recycle()
            }
        } catch (e: IOException) {
            Log.error(TAG, "setWallpaper failed", e)
            SetResult.Failure(e)
        } catch (e: SecurityException) {
            Log.error(TAG, "setWallpaper failed", e)
            SetResult.Failure(e)
        } catch (oom: OutOfMemoryError) {
            Log.error(TAG, "setWallpaper OOM", oom)
            SetResult.Failure(RuntimeException("Out of memory: image too large", oom))
        }
    }

    /**
     * Compute source-pixel crop rect from the current viewport state.
     *
     * @param srcW         Source bitmap width.
     * @param srcH         Source bitmap height.
     * @param baseDisplayW Display width of source when fitted (no zoom).
     * @param baseDisplayH Display height of source when fitted (no zoom).
     * @param zoom         Current zoom multiplier (>= cover-min-scale).
     * @param offsetX/Y    Current pan offset in px (graphicsLayer translation).
     * @param viewportW/H  The visible crop window size in px (device pixels).
     * @param containerW/H The parent container size for centering math.
     */
    fun computeCropRect(
        srcW: Int,
        srcH: Int,
        baseDisplayW: Float,
        baseDisplayH: Float,
        zoom: Float,
        offsetX: Float,
        offsetY: Float,
        viewportW: Float,
        viewportH: Float,
        containerW: Float,
        containerH: Float,
    ): Rect {
        if (!hasValidCropInputs(srcW, srcH, baseDisplayW, baseDisplayH)) {
            return Rect(0, 0, srcW.coerceAtLeast(1), srcH.coerceAtLeast(1))
        }
        val effW = baseDisplayW * zoom
        val effH = baseDisplayH * zoom

        // Where the effective displayed image is positioned inside the container
        // (centered + pan offset)
        val imgLeft = (containerW - effW) / 2f + offsetX
        val imgTop = (containerH - effH) / 2f + offsetY

        // Where the viewport rect is positioned inside the container (centered)
        val vpLeft = (containerW - viewportW) / 2f
        val vpTop = (containerH - viewportH) / 2f

        // Top-left of the viewport relative to the effective image's top-left
        val relLeft = vpLeft - imgLeft
        val relTop = vpTop - imgTop

        val scaleX = srcW / effW
        val scaleY = srcH / effH

        val cropLeft = (relLeft * scaleX).roundToInt().coerceIn(0, srcW - 1)
        val cropTop = (relTop * scaleY).roundToInt().coerceIn(0, srcH - 1)
        val cropW = (viewportW * scaleX).roundToInt().coerceIn(1, srcW - cropLeft)
        val cropH = (viewportH * scaleY).roundToInt().coerceIn(1, srcH - cropTop)

        return Rect(cropLeft, cropTop, cropLeft + cropW, cropTop + cropH)
    }

    /**
     * Minimum zoom that ensures the image covers the viewport (no letterbox gap).
     */
    fun coverMinScale(
        baseDisplayW: Float,
        baseDisplayH: Float,
        viewportW: Float,
        viewportH: Float,
    ): Float {
        if (baseDisplayW <= 0f || baseDisplayH <= 0f) return 1f
        return max(viewportW / baseDisplayW, viewportH / baseDisplayH).coerceAtLeast(1f)
    }
}
