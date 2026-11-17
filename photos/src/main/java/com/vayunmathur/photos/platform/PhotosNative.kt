package com.vayunmathur.photos.platform

/**
 * JNI bridge to the native `photos_fx` Rust library: performance-critical
 * pixel-processing filters (stylize, unsharp mask, liquify, blur, content-aware
 * fill). All pixel arrays are ARGB (0xAARRGGBB), exactly as `Bitmap.getPixels`
 * returns them.
 */
object PhotosNative {
    init {
        System.loadLibrary("photos_fx")
    }

    /** Stylize filter. mode: 0=None, 1=FindEdges (Sobel), 2=Emboss. */
    external fun stylize(pixels: IntArray, w: Int, h: Int, mode: Int): IntArray

    /** Unsharp mask (gaussian blur + per-channel sharpening). */
    external fun unsharp(
        pixels: IntArray,
        w: Int,
        h: Int,
        amount: Float,
        radius: Float,
        threshold: Int,
    ): IntArray

    /**
     * Liquify: displacement field + bilinear resample. Ops are marshalled as
     * [tools] (one LiquifyTool ordinal per op: Push=0, Twirl=1, Pucker=2,
     * Bloat=3, Reconstruct=4) and [params] with 6 floats per op in order
     * [x, y, dx, dy, radius, strength].
     */
    external fun liquify(
        pixels: IntArray,
        w: Int,
        h: Int,
        tools: IntArray,
        params: FloatArray,
    ): IntArray

    /** Masked lens blur. mode: 0=Radial, 1=Linear, 2=Lens. */
    external fun blurParams(
        pixels: IntArray,
        w: Int,
        h: Int,
        mode: Int,
        centerX: Float,
        centerY: Float,
        radius: Float,
        intensity: Float,
        feather: Float,
        angle: Float,
    ): IntArray

    /** Full-image filter blur. mode: 0=Gaussian, 1=Motion, 2=Radial, 3=Spin. */
    external fun filterBlur(
        pixels: IntArray,
        w: Int,
        h: Int,
        mode: Int,
        amount: Float,
        angle: Float,
        centerX: Float,
        centerY: Float,
    ): IntArray

    /** Content-aware fill (exemplar synthesis / diffusion fallback). */
    external fun inpaint(
        pixels: IntArray,
        w: Int,
        h: Int,
        holeMask: FloatArray,
        maskW: Int,
        maskH: Int,
        passes: Int,
    ): IntArray
}
