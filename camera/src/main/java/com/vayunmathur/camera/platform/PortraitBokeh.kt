package com.vayunmathur.camera.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import com.vayunmathur.library.log.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.vayunmathur.library.ml.ModnetSegmenter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pseudo-bokeh used by both the portrait viewfinder ([com.vayunmathur.camera.ui.CameraScreen]) and
 * the still-capture path ([StillBokehRenderer]), so a saved photo matches what the preview showed.
 *
 * 3-pass separable blur along the 0°/120°/240° axes, averaged, then cross-faded back to the sharp
 * input by the segmentation mask's alpha (1 = subject, 0 = background).
 */
internal const val BOKEH_SHADER = """
    uniform shader cameraInput;
    uniform shader alphaMask;
    uniform float blurScale;

    half4 main(float2 fragCoord) {
        float maskAlpha = alphaMask.eval(fragCoord).a;
        half4 sharp = cameraInput.eval(fragCoord);

        // 3-pass separable bokeh: blur along 0°, 120°, 240° axes then average.
        // Each pass samples 13 taps along its direction (radius ~36px).
        float2 dir0 = float2(1.0, 0.0);
        float2 dir1 = float2(-0.5, 0.866);
        float2 dir2 = float2(-0.5, -0.866);

        // 1D Gaussian-ish weights for 13 taps (symmetric, sum ≈ 1)
        float w0 = 0.14;
        float w1 = 0.13;
        float w2 = 0.11;
        float w3 = 0.09;
        float w4 = 0.06;
        float w5 = 0.04;
        float w6 = 0.02;

        half4 pass0 = cameraInput.eval(fragCoord) * w0;
        half4 pass1 = cameraInput.eval(fragCoord) * w0;
        half4 pass2 = cameraInput.eval(fragCoord) * w0;

        for (float i = 1.0; i <= 6.0; i += 1.0) {
            float w;
            if (i < 1.5) w = w1;
            else if (i < 2.5) w = w2;
            else if (i < 3.5) w = w3;
            else if (i < 4.5) w = w4;
            else if (i < 5.5) w = w5;
            else w = w6;

            float2 off0 = dir0 * i * 6.0 * blurScale;
            float2 off1 = dir1 * i * 6.0 * blurScale;
            float2 off2 = dir2 * i * 6.0 * blurScale;

            pass0 += cameraInput.eval(fragCoord + off0) * w;
            pass0 += cameraInput.eval(fragCoord - off0) * w;
            pass1 += cameraInput.eval(fragCoord + off1) * w;
            pass1 += cameraInput.eval(fragCoord - off1) * w;
            pass2 += cameraInput.eval(fragCoord + off2) * w;
            pass2 += cameraInput.eval(fragCoord - off2) * w;
        }

        half4 blur = (pass0 + pass1 + pass2) / 3.0;
        return mix(blur, sharp, maskAlpha);
    }
"""

/** Blur-strength mapping: base scale plus range over the 0..1 slider. */
private const val BOKEH_BLUR_BASE = 0.4f
private const val BOKEH_BLUR_RANGE = 1.4f

/** Maps the 0..1 "blur strength" slider onto the shader's tap-spacing multiplier. */
internal fun bokehBlurScale(strength: Float): Float =
    BOKEH_BLUR_BASE + strength.coerceIn(0f, 1f) * BOKEH_BLUR_RANGE

/**
 * Two-pass separable Gaussian blur (radius 3, sigma ~1.5) of a row-major foreground-probability
 * mask, which softens the segmenter's hard edges. [temp] and [dst] are w*h scratch buffers owned by
 * the caller so the per-frame preview path can reuse them across frames; the result is [dst].
 */
internal fun blurMask(src: FloatArray, w: Int, h: Int, temp: FloatArray, dst: FloatArray): FloatArray {
    blurHorizontal(src, w, h, temp)
    blurVertical(temp, w, h, dst)
    return dst
}

/** Horizontal Gaussian pass of [src] into [temp]; clamps taps at the row edges. */
private fun blurHorizontal(src: FloatArray, w: Int, h: Int, temp: FloatArray) {
    // Horizontal pass – manual clamp instead of coerceIn in inner loop
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            temp[row + x] = blurTapRow(src, row, x, w)
        }
    }
}

/** One horizontal kernel evaluation centered at ([row], x). */
private fun blurTapRow(src: FloatArray, row: Int, x: Int, w: Int): Float {
    var sum = 0f
    var sx: Int
    // k = -3
    sx = x - BLUR_RADIUS
    if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
    sum += src[row + sx] * GAUSS_EDGE
    // k = -2
    sx = x - 2
    if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
    sum += src[row + sx] * GAUSS_MID
    // k = -1
    sx = x - 1
    if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
    sum += src[row + sx] * GAUSS_NEAR
    // k = 0
    sum += src[row + x] * GAUSS_CENTER
    // k = 1
    sx = x + 1
    if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
    sum += src[row + sx] * GAUSS_NEAR
    // k = 2
    sx = x + 2
    if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
    sum += src[row + sx] * GAUSS_MID
    // k = 3
    sx = x + BLUR_RADIUS
    if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
    sum += src[row + sx] * GAUSS_EDGE
    return sum
}

/** Vertical Gaussian pass of [temp] into [dst]; clamps taps at the column edges. */
private fun blurVertical(temp: FloatArray, w: Int, h: Int, dst: FloatArray) {
    // Vertical pass
    for (x in 0 until w) {
        for (y in 0 until h) {
            dst[y * w + x] = blurTapColumn(temp, x, y, w, h)
        }
    }
}

/** One vertical kernel evaluation centered at column x, row y. */
private fun blurTapColumn(temp: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
    var sum = 0f
    var sy: Int
    sy = y - BLUR_RADIUS
    if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
    sum += temp[sy * w + x] * GAUSS_EDGE
    sy = y - 2
    if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
    sum += temp[sy * w + x] * GAUSS_MID
    sy = y - 1
    if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
    sum += temp[sy * w + x] * GAUSS_NEAR
    sum += temp[y * w + x] * GAUSS_CENTER
    sy = y + 1
    if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
    sum += temp[sy * w + x] * GAUSS_NEAR
    sy = y + 2
    if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
    sum += temp[sy * w + x] * GAUSS_MID
    sy = y + BLUR_RADIUS
    if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
    sum += temp[sy * w + x] * GAUSS_EDGE
    return sum
}

/**
 * Packs a foreground-probability mask into a white ARGB_8888 bitmap whose alpha is that
 * probability, which is the form both the preview shader and [StillBokehRenderer] sample.
 * [pixels] is a w*h scratch buffer owned by the caller (reused per frame by the preview).
 */
internal fun maskToBitmap(mask: FloatArray, w: Int, h: Int, pixels: IntArray): Bitmap {
    for (i in 0 until w * h) {
        val v = mask[i]
        val clamped = when {
            v < 0f -> 0f
            v > 1f -> 1f
            else -> v
        }
        pixels[i] = Color.argb(
            (clamped * CHANNEL_MAX).toInt(),
            CHANNEL_MAX_INT,
            CHANNEL_MAX_INT,
            CHANNEL_MAX_INT
        )
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/** Separable Gaussian kernel (radius 3): edge/mid/near/center tap weights. */
private const val BLUR_RADIUS = 3
private const val GAUSS_EDGE = 0.06f
private const val GAUSS_MID = 0.12f
private const val GAUSS_NEAR = 0.18f
private const val GAUSS_CENTER = 0.28f

/** Mask alpha channel scale; full-circle degrees for rotation checks. */
private const val CHANNEL_MAX = 255f
private const val CHANNEL_MAX_INT = 255
private const val FULL_CIRCLE_DEGREES = 360f

// Segmentation input: MODNet runs at 512x512 internally, so there is nothing to gain from
// feeding it the full-resolution still. Matches the preview analyzer's cap. The downscale and
// the normalise both happen natively now (library/ml/src/main/rust/src/preprocess.rs), so this
// only bounds how much gets copied across JNI.
private const val SEGMENT_INPUT_MAX_SIDE = 512

// The shader's tap spacing is in surface pixels and was tuned against a phone-sized viewfinder, so
// running it straight on a 12MP still would both under-blur relative to the preview and spread the
// fixed 13 taps far enough apart to ghost. Instead blur a viewfinder-sized copy with the exact
// preview parameters and scale the (already soft) result back up when compositing.
private const val BLUR_LONG_SIDE = 1440

/**
 * Bakes the portrait bokeh the viewfinder previews into a captured still.
 *
 * Owns its own [ModnetSegmenter] (created on first use) because the preview's
 * [SelfieSegmenter][com.vayunmathur.library.ml.SelfieSegmenter] lives in
 * [BokehAnalyzer], which is disposed with the composable and runs on a different thread. The two
 * share one `VkDevice` regardless — `:library:ml` reference-counts it — so this costs buffers and
 * pipelines, not a second driver context.
 *
 * MODNet rather than Selfie: the still path runs once per shutter, so it can afford the
 * 512×512 alpha matte with strand-level hair detail; the preview keeps the cheaper
 * 256×256 Selfie pass at ~15 fps.
 */
class StillBokehRenderer(private val context: Context) : AutoCloseable {

    private var segmenter: ModnetSegmenter? = null
    @Volatile private var closed = false

    /**
     * Returns [src] with its background blurred, the warmth/shadows matrix baked in and the frame
     * mirrored when [mirror] is set — i.e. everything the preview applied on top of the raw frame.
     * [src] is expected in sensor orientation (rotation left to the EXIF tag, as the rest of the
     * capture path does), with [rotationDegrees] describing how far it has to be rotated to sit
     * upright; segmentation runs on an upright copy since the model expects upright subjects.
     *
     * Recycles [src] and returns the new bitmap on success. Returns null (leaving [src] intact for
     * the caller to fall back on) if segmentation or the GPU pass fails.
     */
    @Synchronized
    fun render(
        src: Bitmap,
        rotationDegrees: Int,
        strength: Float,
        warmth: Float,
        shadows: Float,
        mirror: Boolean,
    ): Bitmap? {
        if (closed) return null
        // The blur pass is AGSL, which needs RuntimeShader (API 33+). Below that
        // there is no bokeh; the caller keeps the sharp frame.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val mask = segmentQuietly(src, rotationDegrees) ?: return null

        // Blurred background, viewfinder-sized: alpha is punched out where the subject is so it can
        // be laid straight over the sharp frame.
        val background = blurQuietly(src, mask, strength)
        mask.recycle()
        if (background == null) return null

        return compositeQuietly(src, background, warmth, shadows, mirror).also {
            background.recycle()
        }
    }

    /** Segments [src]; null (logged) on failure. */
    private fun segmentQuietly(src: Bitmap, rotationDegrees: Int): Bitmap? {
        return try {
            buildMask(src, rotationDegrees)
        } catch (e: IllegalStateException) {
            Log.error("StillBokeh", "segmentation failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error("StillBokeh", "segmentation failed", e)
            null
        }
    }

    /** Blurs [src] behind the subject mask; null (logged) on failure. */
    private fun blurQuietly(src: Bitmap, mask: Bitmap, strength: Float): Bitmap? {
        return try {
            blurBackground(src, mask, strength)
        } catch (e: IllegalStateException) {
            Log.error("StillBokeh", "blur pass failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error("StillBokeh", "blur pass failed", e)
            null
        }
    }

    /** Composites the blurred background over the color-adjusted sharp frame. */
    private fun compositeQuietly(
        src: Bitmap,
        background: Bitmap,
        warmth: Float,
        shadows: Float,
        mirror: Boolean
    ): Bitmap? {
        return try {
            compositeFrame(src, background, warmth, shadows, mirror)
        } catch (e: IllegalStateException) {
            Log.error("StillBokeh", "composite failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error("StillBokeh", "composite failed", e)
            null
        }
    }

    /** Draws the sharp color-adjusted frame with the blurred background over it. */
    private fun compositeFrame(
        src: Bitmap,
        background: Bitmap,
        warmth: Float,
        shadows: Float,
        mirror: Boolean
    ): Bitmap {
        val out = createBitmap(src.width, src.height)
        val canvas = Canvas(out)
        if (mirror) canvas.scale(-1f, 1f, src.width / 2f, src.height / 2f)
        canvas.drawBitmap(
            src, 0f, 0f,
            Paint().apply {
                colorFilter = ColorMatrixColorFilter(buildColorAdjustmentMatrix(warmth, shadows))
            }
        )
        // Upscaling the blurred background costs no detail – it has none left – and bilinear
        // filtering also smooths the 256px mask's alpha edge in the process.
        canvas.drawBitmap(
            background,
            null,
            Rect(0, 0, src.width, src.height),
            Paint().apply { isFilterBitmap = true }
        )
        src.recycle()
        return out
    }

    @Synchronized
    override fun close() {
        closed = true
        try { segmenter?.close() } catch (_: Exception) {}
        segmenter = null
    }

    /**
     * Runs the segmenter on a downscaled, upright copy of [src] and returns the softened mask
     * rotated back into [src]'s orientation, or null when the GPU cannot run it — in which case
     * the caller keeps the sharp frame.
     */
    private fun buildMask(src: Bitmap, rotationDegrees: Int): Bitmap? {
        val seg = segmenter ?: ModnetSegmenter(context).also { segmenter = it }

        // Both helpers hand back their input when there is nothing to do, so only recycle the
        // intermediates we actually allocated – never the caller's bitmap.
        val downscaled = scaleToMaxSide(src, SEGMENT_INPUT_MAX_SIDE)
        val upright = rotate(downscaled, rotationDegrees.toFloat())
        if (downscaled !== src && downscaled !== upright) downscaled.recycle()

        val result = (
            try {
                seg.segment(upright)
            } finally {
                if (upright !== src) upright.recycle()
            }
            ) ?: return null

        val w = result.width
        val h = result.height
        val softened = blurMask(result.mask, w, h, FloatArray(w * h), FloatArray(w * h))
        val mask = maskToBitmap(softened, w, h, IntArray(w * h))

        val sensorSpace = rotate(mask, -rotationDegrees.toFloat())
        if (sensorSpace !== mask) mask.recycle()
        return sensorSpace
    }

    /**
     * Renders the bokeh shader over a viewfinder-sized copy of [src], then clears the subject out
     * of it (DST_OUT against the mask's alpha) so only the blurred background is left.
     */
    private fun blurBackground(src: Bitmap, mask: Bitmap, strength: Float): Bitmap? {
        val scale = min(1f, BLUR_LONG_SIDE.toFloat() / max(src.width, src.height))
        val small = if (scale < 1f) {
            src.scale(
                (src.width * scale).roundToInt().coerceAtLeast(1),
                (src.height * scale).roundToInt().coerceAtLeast(1)
            )
        } else {
            src
        }
        // Preview parameters verbatim; the trailing factor only matters for stills whose long side
        // came in under BLUR_LONG_SIDE and so could not be scaled to the viewfinder's pixel pitch.
        val blurScale = bokehBlurScale(strength) *
            (max(small.width, small.height).toFloat() / BLUR_LONG_SIDE)

        val blurred = try {
            renderBlur(small, blurScale)
        } finally {
            if (small !== src) small.recycle()
        }
        if (blurred == null) return null

        Canvas(blurred).drawBitmap(
            mask,
            null,
            Rect(0, 0, blurred.width, blurred.height),
            Paint().apply {
                isFilterBitmap = true
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
        )
        return blurred
    }

    /**
     * Draws [src] through [BOKEH_SHADER] into an offscreen GPU surface — AGSL only runs on a
     * hardware canvas, so there is a HardwareRenderer/ImageReader pair rather than a plain Canvas.
     * The shader samples [src] through a CLAMP BitmapShader (instead of the preview's RenderEffect)
     * so taps past the frame edge repeat the border rather than fading the edges to transparent.
     */
    private fun renderBlur(src: Bitmap, blurScale: Float): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val w = src.width
        val h = src.height
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val node = RenderNode("still-bokeh")
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            node.setPosition(0, 0, w, h)

            val shader = RuntimeShader(BOKEH_SHADER)
            shader.setInputShader(
                "cameraInput",
                BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            // Background-only pass: a fully transparent mask makes the shader's mix() return the
            // blur everywhere. The subject is restored by compositing this over the sharp frame.
            shader.setInputShader(
            "alphaMask",
            BitmapShader(TRANSPARENT_1X1, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        )
            shader.setFloatUniform("blurScale", blurScale)

            val canvas = node.beginRecording()
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
            node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            val image = reader.acquireNextImage() ?: return null
            return image.use { img ->
                val buffer = img.hardwareBuffer ?: return@use null
                buffer.use { hb ->
                    val hardware = Bitmap.wrapHardwareBuffer(hb, null) ?: return@use null
                    hardware.copy(Bitmap.Config.ARGB_8888, true).also { hardware.recycle() }
                }
            }
        } finally {
            node.discardDisplayList()
            renderer.destroy()
            reader.close()
        }
    }

    private fun scaleToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return src.scale(
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        if (degrees % FULL_CIRCLE_DEGREES == 0f) return src
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private companion object {
        val TRANSPARENT_1X1: Bitmap =
            createBitmap(1, 1).apply { eraseColor(Color.TRANSPARENT) }
    }
}
