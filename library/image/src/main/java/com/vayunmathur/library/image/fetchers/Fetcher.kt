package com.vayunmathur.library.image.fetchers

import android.content.Context
import android.graphics.ImageDecoder
import java.io.InputStream

/** Raw fetched payload handed to the decoders. */
sealed class FetchResult {
    /** Encoded image or video bytes fetched from a source. */
    data class Bytes(val bytes: ByteArray, val isVideo: Boolean = false) : FetchResult()
    /** Already-decoded bitmap fetched from a source. */
    data class BitmapResult(val bitmap: android.graphics.Bitmap) : FetchResult()

    /**
     * Data that is already on local storage, exposed as a re-openable stream.
     *
     * Lets the decoder sample straight from the file rather than having the loader
     * first materialise the whole encoded image as a `ByteArray`: during a fast
     * scroll that was one multi-MB allocation per visible tile, to produce a
     * 256 px thumbnail.
     *
     * [openStream] is the fallback for content [decoderSource] cannot handle (an
     * SVG, or a video the frame decoder has to open).
     */
    class Source(
        val decoderSource: ImageDecoder.Source,
        val openStream: () -> InputStream?,
    ) : FetchResult()
}

/** Source adapter turning request data into a FetchResult, or null if unsupported. */
interface Fetcher {
    suspend fun fetch(data: Any?, context: Context): FetchResult?
}
