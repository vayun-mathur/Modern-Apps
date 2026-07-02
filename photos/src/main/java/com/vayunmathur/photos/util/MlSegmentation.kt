package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.vayunmathur.photos.data.Selection

/**
 * Auto-select the foreground subject with an on-device neural model (DeepLabV3
 * via MediaPipe Tasks — standalone, NO Google Play Services). Pixels whose
 * predicted class is not background (index 0) form the selection. Runs off the
 * main thread; [onResult] is posted to the main thread (null on failure).
 */
fun segmentSubject(context: Context, bitmap: Bitmap, onResult: (Selection?) -> Unit) {
    Thread {
        val sel = runCatching { runSegmenter(context, bitmap) }
            .getOrElse { Log.e("MlSegmentation", "segmentation failed", it); null }
        Handler(Looper.getMainLooper()).post { onResult(sel) }
    }.start()
}

private fun runSegmenter(context: Context, bitmap: Bitmap): Selection? {
    // Cap input size for speed; the mask is returned at the input resolution.
    val maxDim = 512
    val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val scaled = if (w == bitmap.width && h == bitmap.height) bitmap
    else Bitmap.createScaledBitmap(bitmap, w, h, true)
    val argb = if (scaled.config == Bitmap.Config.ARGB_8888) scaled
    else scaled.copy(Bitmap.Config.ARGB_8888, false)

    val options = ImageSegmenter.ImageSegmenterOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("deeplab_v3.tflite").build())
        .setRunningMode(RunningMode.IMAGE)
        .setOutputCategoryMask(true)
        .setOutputConfidenceMasks(false)
        .build()

    ImageSegmenter.createFromOptions(context, options).use { segmenter ->
        val result = segmenter.segment(BitmapImageBuilder(argb).build())
        val maskImage = result.categoryMask().orElse(null) ?: return null
        val mw = maskImage.width
        val mh = maskImage.height
        val buffer = ByteBufferExtractor.extract(maskImage)
        val arr = FloatArray(mw * mh)
        for (i in 0 until mw * mh) {
            val cls = buffer.get(i).toInt() and 0xFF
            arr[i] = if (cls != 0) 1f else 0f
        }
        // Feather the hard class edge slightly so cutouts aren't jagged.
        return Selection(arr, mw, mh).applyFeather(1.5f)
    }
}
