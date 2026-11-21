package com.vayunmathur.pdf.platform
import com.vayunmathur.library.log.Log
import java.nio.ByteBuffer

/**
 * The raster half of [SafePdfParser]: image payload decoding and its budgets.
 *
 * Split out so SafePdfParser.kt stays under the FileLength limit. The pixel and
 * byte budgets move with the decoder that enforces them; [SafePdfParser] keeps
 * referencing them through this object.
 */
object SafePdfImages {

    /**
     * Decoded-pixel budget, deliberately a SEPARATE constant from [MAX_IMAGE_DATA_BYTES] even
     * though the two currently hold the same literal: one counts compressed bytes on the wire,
     * the other counts pixels after decode (16 Mi pixels is 64 MB of ARGB_8888). The same
     * conflation exists on the Rust side as `MAX_IMAGE_BYTES` / `MAX_IMAGE_PIXELS`; sharing one
     * literal between the two is how a change to either silently moves the other.
     *
     * Enforced per FORMAT, not globally — see `decodeBitmap`.
     *
     * TWIN of `graphics_state.rs:286`, and the invariant is ONE-DIRECTIONAL:
     * this value must be >= Rust's, never <.
     *
     * `images.rs:848` decimates until `out_w * out_h <= MAX_IMAGE_PIXELS` (Rust's), then the
     * `format == 0` branch of `decodeBitmap` drops anything above this one. If this were
     * LOWERED below Rust's, Rust would emit rasters in the gap believing it had decimated them
     * to fit, and they would vanish here — the large-CCITT bug (F13) silently returning, with
     * both sides individually "correct" and no diagnostic on either. Raising it is safe.
     *
     * Same shape as `wire.rs`'s `WIRE_VERSION <= kotlin_version` check: the consumer may run
     * ahead of the producer, never behind. Written as a bare literal with an explicit type so
     * that check's file-reading approach can be pointed at this line too.
     */
    private const val MAX_IMAGE_PIXELS: Long = 16777216 // 16 * 1024 * 1024

    /**
     * TWIN of `graphics_state.rs:284` (used at `images.rs:1078`), with the same
     * consumer->=producer invariant as [MAX_IMAGE_PIXELS]: Rust refuses an image past its
     * value, so a SMALLER value here drops images Rust considered valid.
     */
    private const val MAX_IMAGE_DIM: Int = 20000

    /**
     * Largest raster payload this decoder will materialise for one image, in bytes.
     *
     * It is a LOCAL heap policy, not a wire invariant: Rust's own bound is looser in both
     * arms that reach it. `extract_image` downscales a decoded raster to 2048px on its long
     * side (16 MB of RGBA at worst, exactly this), but `extract_inline_image` only enforces
     * `MAX_IMAGE_PIXELS` (16 MP -> 64 MB of RGBA), and the format-1 JPEG passthrough hands
     * over the stream bytes with only a 64 MB ceiling on them. So a payload above this is a
     * legitimate stream we choose not to decode, NOT evidence that the buffer is corrupt —
     * which is why exceeding it skips the one primitive instead of ending the page.
     */
    internal const val MAX_IMAGE_DATA_BYTES = 16 * 1024 * 1024

    internal fun imageTooLarge(len: Int): Boolean = len > MAX_IMAGE_DATA_BYTES

    internal fun badDimensions(w: Int, h: Int): Boolean =
        w <= 0 || h <= 0 || w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM

    /**
     * Smallest power-of-two subsample bringing `w*h` within [MAX_IMAGE_PIXELS].
     * `BitmapFactory` rounds `inSampleSize` DOWN to a power of two, so compute one directly
     * rather than hand it a ratio it would round the wrong way (a rounded-down sample size
     * decodes LARGER than asked, which is the direction that OOMs).
     */
    internal fun sampleSizeFor(w: Int, h: Int): Int {
        var s = 1
        while ((w.toLong() / s) * (h.toLong() / s) > MAX_IMAGE_PIXELS) s = s shl 1
        return s
    }

    /**
     * Decode an image payload: format 1 = JPEG bytes, 0 = raw RGBA8888
     * (legacy wire layout), 2 = raw ARGB words (see [FORMAT_ARGB_WORDS]).
     */
    internal fun decodeBitmap(w: Int, h: Int, format: Int, data: ByteArray): android.graphics.Bitmap? =
        decodeBitmap(w, h, format, ByteBuffer.wrap(data))

    /**
     * Buffer form of [decodeBitmap]. [data]'s remaining bytes are the payload;
     * its position is left unchanged. The format-2 arm consumes the payload as
     * a slice of this buffer (see [decodeArgbWords]), so a page-sized wire
     * buffer is never copied per image. The JPEG arm may copy only when the
     * buffer has no accessible backing array — compressed bytes, not pixels.
     */
    internal fun decodeBitmap(w: Int, h: Int, format: Int, data: ByteBuffer): android.graphics.Bitmap? {
        if (badDimensions(w, h)) return null
        // The pixel budget is per format. The raw branch used to allocate
        // `w*h` ints before it could do anything, so it must be refused
        // outright — and Rust decimates that path, so an oversized raw image
        // is a contract violation rather than ordinary input. A JPEG commits
        // nothing until the decoder runs and is scaled down there instead.
        if (format != FORMAT_JPEG && w.toLong() * h.toLong() > MAX_IMAGE_PIXELS) return null
        return try {
            when (format) {
                FORMAT_JPEG -> decodeJpeg(w, h, data)
                FORMAT_RAW -> decodeRgba(w, h, data)
                FORMAT_ARGB_WORDS -> decodeArgbWords(w, h, data)
                else -> {
                    // Rust returns no image at all for a format it could not produce, so an
                    // unknown format here is a wire mismatch, not a failed decode to paper over.
                    Log.status("SafePdfParser", "Unknown bitmap format $format")
                    null
                }
            }
        } catch (expected: IllegalArgumentException) {
            Log.status("SafePdfParser", "decodeBitmap failed w=$w h=$h format=$format", expected)
            null
        } catch (expected: NullPointerException) {
            // The stub android.jar's Bitmap.createBitmap(w, h, config)
            // returns null (the int-array overload the old raw path used is
            // a platform type and simply propagated that null). Off-device
            // this is the "no bitmap" outcome the parser already handles;
            // on a device createBitmap does not return null.
            Log.status("SafePdfParser", "decodeBitmap failed w=$w h=$h format=$format", expected)
            null
        } catch (expected: OutOfMemoryError) {
            Log.status("SafePdfParser", "decodeBitmap OOM w=$w h=$h format=$format", expected)
            null
        }
    }

    private fun decodeJpeg(w: Int, h: Int, data: ByteBuffer): android.graphics.Bitmap? {
        val len = data.remaining()
        if (len > MAX_IMAGE_DATA_BYTES) {
            Log.status("SafePdfParser", "JPEG too large $len")
            return null
        }
        // Subsample instead of dropping. Rust hands the JPEG over at full
        // dimensions deliberately (images.rs:1082-1087) because this decoder
        // is what is supposed to scale it; without inSampleSize it decodes at
        // full size, so a 20 MP photo would commit ~80 MB of ARGB_8888.
        val opts = android.graphics.BitmapFactory.Options()
        opts.inSampleSize = sampleSizeFor(w, h)
        return if (data.hasArray()) {
            android.graphics.BitmapFactory.decodeByteArray(
                data.array(), data.arrayOffset() + data.position(), len, opts
            )
        } else {
            val bytes = ByteArray(len)
            data.duplicate().get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }

    /**
     * Format 2: raw ARGB words -> ARGB_8888 bitmap with no per-pixel loop
     * and no intermediate pixel array.
     *
     * Before (format 0, the only raw layout): wire bytes -> per-image
     * `ByteArray` -> `IntArray(w*h)` (one RGBA->ARGB iteration per pixel)
     * -> bitmap: at the 16 MP cap that is 64 MB + 64 MB transient on top of
     * the 64 MB bitmap. Android stores ARGB_8888 pixels as little-endian
     * ARGB words (B,G,R,A bytes in memory), which the wire's RGBA8888 bytes
     * are NOT — a plain [android.graphics.Bitmap.copyPixelsFromBuffer] over
     * RGBA swaps R and B (pinned by `SafePdfImagesZeroCopyTest`). So the
     * Rust serializer now emits format 2: each pixel as an ARGB word, which
     * is byte-for-byte the bitmap's in-memory layout. The caller hands a
     * slice/view of the already-materialised wire buffer; we allocate only
     * the bitmap and let `copyPixelsFromBuffer` move the bytes.
     */
    private fun decodeArgbWords(w: Int, h: Int, data: ByteBuffer): android.graphics.Bitmap? {
        if (data.remaining().toLong() < w.toLong() * h * BYTES_PER_PIXEL) return null
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        // The wire carries straight (unpremultiplied) alpha, while a bitmap's
        // storage is premultiplied by default: copying the bytes in without
        // this flag would have draw/getPixel un-premultiply them a second
        // time and brighten every translucent pixel. The flag relabels the
        // storage without converting it, so drawing premultiplies once,
        // exactly as the int-array path did.
        bitmap.setPremultiplied(false)
        // copyPixelsFromBuffer consumes from the buffer's position, so hand
        // it a duplicate: the caller's position must stay where the next
        // primitive starts being measured from.
        bitmap.copyPixelsFromBuffer(data.duplicate())
        return bitmap
    }

    /**
     * Format 0 (legacy RGBA8888, no longer emitted by the in-repo Rust
     * serializer) and tiling-pattern cells, whose tag carries no format
     * byte to signal the new layout with. Kept so older buffers still
     * decode; pattern cells are small, and this is not the embedded-image
     * path the 16 MP budget applies to.
     */
    private fun decodeRgba(w: Int, h: Int, data: ByteBuffer): android.graphics.Bitmap? {
        if (data.remaining().toLong() < w.toLong() * h * BYTES_PER_PIXEL) return null
        val pixels = IntArray(w * h)
        val view = data.duplicate()
        for (i in pixels.indices) {
            val r = view.get().toInt() and CHANNEL_MASK
            val g = view.get().toInt() and CHANNEL_MASK
            val b = view.get().toInt() and CHANNEL_MASK
            val a = view.get().toInt() and CHANNEL_MASK
            pixels[i] = (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
        }
        return android.graphics.Bitmap.createBitmap(
            pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888
        )
    }

    private const val FORMAT_JPEG = 1
    private const val FORMAT_RAW = 0

    /** Raw pixels as little-endian ARGB words (B,G,R,A bytes in memory). */
    internal const val FORMAT_ARGB_WORDS = 2
    private const val CHANNEL_MASK = 0xFF
    private const val BYTES_PER_PIXEL = 4L
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
